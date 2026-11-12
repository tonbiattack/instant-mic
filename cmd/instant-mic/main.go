// Command instant-mic is the Windows receiver for instant-mic.
//
// It listens only on the local network, accepts authenticated WebSocket
// messages from a paired Android phone, and types the result into the focused
// window with SendInput.
package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/tonbiattack/instant-mic/internal/autostart"
	"github.com/tonbiattack/instant-mic/internal/config"
	"github.com/tonbiattack/instant-mic/internal/input"
	"github.com/tonbiattack/instant-mic/internal/netutil"
	"github.com/tonbiattack/instant-mic/internal/server"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	enableUTF8Console()
	fs := flag.NewFlagSet("instant-mic", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)

	var (
		addr       = fs.String("addr", "", "listen address (default from config, :8765)")
		configPath = fs.String("config", "", "config file path (default in the user config dir)")
		noTLS      = fs.Bool("no-tls", false, "disable TLS; only for local debugging")
		pairTTL    = fs.Duration("pair-ttl", 10*time.Minute, "how long a pairing code stays valid")
		showPair   = fs.Bool("pair", false, "start running and immediately open pairing")
		listDev    = fs.Bool("devices", false, "list paired devices and exit")
		unpair     = fs.String("unpair", "", "remove a paired device by id/name and exit")
		setPrefix  = fs.String("command-prefix", "", "set the voice command prefix and exit")
		verbose    = fs.Bool("verbose", false, "log connection details")
		trayMode   = fs.Bool("tray", false, "run with a notification-area icon and no console output")
		noDiscover = fs.Bool("no-discovery", false, "disable LAN mDNS advertisement")
		autoStart  = fs.String("autostart", "", "on|off: start at Windows login; report the current state when empty")
	)
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}

	path := *configPath
	if path == "" {
		p, err := config.DefaultPath()
		if err != nil {
			return err
		}
		path = p
	}
	store, err := config.Open(path)
	if err != nil {
		return err
	}

	if *listDev {
		return printDevices(store)
	}
	if *unpair != "" {
		return removeDevice(store, *unpair)
	}
	if *setPrefix != "" {
		if err := store.SetCommandPrefix(*setPrefix); err != nil {
			return err
		}
		fmt.Println("コマンド接頭語を更新しました:", *setPrefix)
		return nil
	}
	if *autoStart != "" {
		return setAutostart(store, *autoStart)
	}
	if *noDiscover {
		if err := store.SetDiscoveryEnabled(false); err != nil {
			return err
		}
		fmt.Println("LAN内の自動検出を無効にしました。")
		return nil
	}
	if !*trayMode {
		if enabled, err := autostart.Enabled(); err == nil && enabled && !*showPair {
			fmt.Println("ヒント: Windowsログイン時に自動起動します（無効化: -autostart off）")
		}
	}

	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level}))

	cfg := store.Snapshot()
	listen := cfg.ListenAddr
	if *addr != "" {
		listen = *addr
	}
	if !strings.Contains(listen, ":") {
		listen = ":" + listen
	}

	var tlsConf *tls.Config
	var fingerprint string
	if !*noTLS {
		cert, fp, err := netutil.EnsureCert(filepath.Dir(path), netutil.LocalIPs())
		if err != nil {
			return fmt.Errorf("prepare TLS certificate: %w", err)
		}
		fingerprint = fp
		tlsConf = &tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
		}
	}

	srv := server.New(server.Config{
		Store:       store,
		Inserter:    input.New(),
		TLS:         tlsConf,
		Logger:      logger,
		Fingerprint: fingerprint,
		Advertise:   advertiseFlag(!cfg.DisableDiscovery),
	})
	if err := srv.Start(listen); err != nil {
		return fmt.Errorf("listen on %s: %w", listen, err)
	}
	defer srv.Close()

	if *trayMode {
		// The tray has no console; status is visible from the icon menu.
		return runTray(srv, store, *pairTTL, *showPair)
	}

	if !*trayMode {
		printBanner(srv, store, tlsConf != nil, fingerprint)
		if *showPair {
			openPairing(srv, *pairTTL, *verbose)
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	quit := make(chan struct{})
	go consoleLoop(ctx, srv, store, *pairTTL, *verbose, quit)

	select {
	case <-ctx.Done():
	case <-quit:
	}
	fmt.Println("\n終了します…")
	return nil
}

func advertiseFlag(enabled bool) *bool { return &enabled }

func printBanner(srv *server.Server, store *config.Store, tlsEnabled bool, fingerprint string) {
	cfg := store.Snapshot()
	port := srv.Port()
	supported := "ws"
	if tlsEnabled {
		supported = "wss"
	}

	fmt.Println("instant-mic receiver")
	fmt.Println("  サーバー名 :", cfg.ServerName)
	fmt.Println("  待ち受け   :", srv.Addr())
	if tlsEnabled {
		fmt.Println("  暗号化     : TLS (自己署名証明書)")
		if fingerprint != "" {
			fmt.Println("  証明書指紋 :", fingerprint)
		}
	} else {
		fmt.Println("  暗号化     : なし（デバッグ用）")
	}

	ips := netutil.LocalIPs()
	fmt.Println("  接続先候補 :")
	if len(ips) == 0 {
		fmt.Println("    (IPv4 アドレスが見つかりません)")
	}
	for _, ip := range ips {
		fmt.Printf("    %s://%s:%d\n", supported, ip, port)
	}
	if !cfg.DisableDiscovery {
		fmt.Println("  自動検出   : 有効（同じLANのスマホから自動で見つかります）")
	} else {
		fmt.Println("  自動検出   : 無効")
	}

	fmt.Println()
	fmt.Println("  操作: [p] ペアリング開始（QR表示）  [d] 端末一覧  [q] 終了")
	fmt.Println("  ※ 入力先アプリをクリックしてフォーカスしたまま音声入力してください")
	fmt.Println()

	if len(store.Devices()) == 0 {
		fmt.Println("  ペアリング済み端末はありません。[p] で開始してください。")
		fmt.Println()
	}
}

func openPairing(srv *server.Server, ttl time.Duration, verbose bool) {
	code, expires, qr, err := srv.BeginPairingQR(ttl)
	if err != nil {
		fmt.Println("ペアリングを開始できません:", err)
		return
	}
	fmt.Println()
	fmt.Println("  ペアリングコード:", code)
	fmt.Printf("  有効期限: %s（%.0f分）\n", expires.Format("15:04:05"), ttl.Minutes())
	fmt.Println()
	fmt.Println("  スマホの「QRでペアリング」で以下を読み取るか、6桁コードを入力してください。")
	fmt.Println()
	fmt.Print(qr.Payload.RenderQRText())
	if verbose {
		fmt.Println("  QRペイロード:", qr.Payload.String())
	}
	fmt.Println()
}

func consoleLoop(ctx context.Context, srv *server.Server, store *config.Store, ttl time.Duration, verbose bool, quit chan<- struct{}) {
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		select {
		case <-ctx.Done():
			return
		default:
		}
		switch strings.ToLower(strings.TrimSpace(scanner.Text())) {
		case "p", "pair":
			openPairing(srv, ttl, verbose)
		case "c", "cancel":
			srv.CancelPairing()
		case "d", "devices":
			_ = printDevices(store)
		case "q", "quit", "exit":
			close(quit)
			return
		case "":
		default:
			fmt.Println("  p=ペアリング / d=端末一覧 / q=終了")
		}
	}
	// stdin closed (for example when launched from a service): keep serving.
	<-ctx.Done()
}

func printDevices(store *config.Store) error {
	devices := store.Devices()
	if len(devices) == 0 {
		fmt.Println("ペアリング済み端末はありません。")
		return nil
	}
	fmt.Println("ペアリング済み端末:")
	for _, d := range devices {
		name := d.Name
		if name == "" {
			name = "(名前なし)"
		}
		seen := "未接続"
		if !d.LastSeenAt.IsZero() {
			seen = d.LastSeenAt.Local().Format("2006-01-02 15:04")
		}
		fmt.Printf("  %-24s id=%s  最終接続=%s\n", name, d.ID, seen)
	}
	return nil
}

func removeDevice(store *config.Store, target string) error {
	for _, d := range store.Devices() {
		if d.ID != target && d.Name != target {
			continue
		}
		removed, err := store.Unpair(d.ID)
		if err != nil {
			return err
		}
		if removed {
			fmt.Println("端末を削除しました:", d.Name)
			return nil
		}
	}
	return fmt.Errorf("端末が見つかりません: %s", target)
}

// setAutostart turns the login-start registration on or off, or reports it when
// the flag is empty.
func setAutostart(store *config.Store, value string) error {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "on", "enable", "true":
		if err := autostart.Set(true); err != nil {
			return fmt.Errorf("自動起動を設定できません: %w", err)
		}
		fmt.Println("Windowsログイン時に自動起動するように設定しました。")
		fmt.Println("次回からタスクトレイ常駐で起動します。")
		return nil
	case "off", "disable", "false":
		if err := autostart.Set(false); err != nil {
			return fmt.Errorf("自動起動を解除できません: %w", err)
		}
		fmt.Println("自動起動を解除しました。")
		return nil
	case "status":
		enabled, err := autostart.Enabled()
		if err != nil {
			return err
		}
		if enabled {
			fmt.Println("自動起動: 有効")
		} else {
			fmt.Println("自動起動: 無効")
		}
		return nil
	default:
		return fmt.Errorf("-autostart は on / off / status のいずれかです: %q", value)
	}
}
