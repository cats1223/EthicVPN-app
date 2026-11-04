using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using Microsoft.Web.WebView2.Core;
using Forms = System.Windows.Forms;

namespace TechTeamVPN;

/// <summary>The window: WebView2 with the page from Web/, a tray icon, and the bridge to the VPN.</summary>
public partial class MainWindow : Window
{
    private readonly Bridge bridge;
    private readonly Forms.NotifyIcon tray;
    private readonly Forms.ToolStripMenuItem toggleItem;
    private readonly bool connectNow;
    private bool quitting;

    public MainWindow(bool startMinimized, bool connectNow)
    {
        InitializeComponent();
        // Set here rather than in XAML: a value that fails to convert there stops the whole window.
        Web.DefaultBackgroundColor = System.Drawing.Color.Black;
        this.connectNow = connectNow;
        bridge = new Bridge(json => Dispatcher.InvokeAsync(() => Web.CoreWebView2?.PostWebMessageAsString(json)),
            () => Dispatcher.Invoke(() => Clipboard.ContainsText() ? Clipboard.GetText() : ""));
        bridge.NeedsElevation += () => Dispatcher.InvokeAsync(RestartElevated);
        bridge.StatusChanged += () => Dispatcher.InvokeAsync(UpdateTray);

        toggleItem = new Forms.ToolStripMenuItem("Подключить", null, async (_, _) => await ToggleAsync());
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Открыть EthicVPN", null, (_, _) => ShowFromTray());
        menu.Items.Add(toggleItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Выход", null, (_, _) => Quit());
        tray = new Forms.NotifyIcon
        {
            Icon = AppIcon(),
            Text = "EthicVPN",
            Visible = true,
            ContextMenuStrip = menu,
        };
        tray.MouseClick += (_, e) => { if (e.Button == Forms.MouseButtons.Left) ShowFromTray(); };

        Loaded += async (_, _) =>
        {
            try
            {
                await InitWebAsync();
            }
            catch (Exception e)
            {
                MessageBox.Show(this, "Не удалось открыть окно приложения (WebView2):\n\n" + e.Message, "EthicVPN",
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
        };
        SourceInitialized += (_, _) => UseDarkTitleBar();
        if (startMinimized) _ = InitWebAsync();
    }

    private bool webStarted;

    private async Task InitWebAsync()
    {
        if (webStarted) return;
        webStarted = true;
        var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Store.LocalDir, "WebView2"));
        await Web.EnsureCoreWebView2Async(env);
        var core = Web.CoreWebView2;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreDevToolsEnabled = false;
        core.SetVirtualHostNameToFolderMapping("app.techteam", Path.Combine(XrayService.AppDir, "Web"), CoreWebView2HostResourceAccessKind.Allow);
        core.WebMessageReceived += (_, e) => bridge.OnMessage(e.TryGetWebMessageAsString());
        // Links open in the browser, not inside the app.
        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(e.Uri) { UseShellExecute = true });
        };
        core.Navigate("https://app.techteam/index.html");
        core.NavigationCompleted += async (_, _) =>
        {
            if (connectNow || bridge.ConnectOnStart) await bridge.ConnectAsync();
        };
    }

    private async Task ToggleAsync()
    {
        if (bridge.Status is VpnStatus.Connected or VpnStatus.Connecting) bridge.Disconnect();
        else await bridge.ConnectAsync();
    }

    private void UpdateTray()
    {
        var on = bridge.Status is VpnStatus.Connected or VpnStatus.Connecting;
        toggleItem.Text = on ? "Отключить" : "Подключить";
        tray.Text = bridge.Status == VpnStatus.Connected ? "EthicVPN — подключено" : "EthicVPN";
    }

    /// <summary>The tunnel needs admin rights: ask Windows, then hand over to the elevated copy.</summary>
    private void RestartElevated()
    {
        var answer = MessageBox.Show(this,
            "Режим «Весь трафик» создаёт сетевой адаптер, для этого нужны права администратора. Перезапустить EthicVPN с ними?",
            "EthicVPN", MessageBoxButton.OKCancel, MessageBoxImage.Information);
        if (answer != MessageBoxResult.OK) return;
        bridge.Shutdown();
        if (Elevation.RelaunchAsAdmin("--connect")) Quit();
    }

    public void ShowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    /// <summary>Closing the window keeps the VPN running in the tray; "Выход" in the tray menu quits.</summary>
    protected override void OnClosing(CancelEventArgs e)
    {
        if (!quitting && bridge.Status is VpnStatus.Connected or VpnStatus.Connecting)
        {
            e.Cancel = true;
            Hide();
            tray.ShowBalloonTip(2000, "EthicVPN", "VPN работает в фоне. Выход — в меню значка.", Forms.ToolTipIcon.Info);
            return;
        }
        base.OnClosing(e);
        Quit();
    }

    private void Quit()
    {
        if (quitting) return;
        quitting = true;
        bridge.Shutdown();
        tray.Visible = false;
        tray.Dispose();
        Application.Current.Shutdown();
    }

    /// <summary>The exe's own icon (ApplicationIcon); the window takes it from the exe as well.</summary>
    private static System.Drawing.Icon AppIcon()
    {
        try
        {
            return System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application;
        }
        catch (Exception)
        {
            return System.Drawing.SystemIcons.Application;
        }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    /// <summary>A black title bar to match the app (Windows 10 2004+ and 11).</summary>
    private void UseDarkTitleBar()
    {
        var on = 1;
        DwmSetWindowAttribute(new WindowInteropHelper(this).Handle, 20, ref on, sizeof(int));
    }
}
