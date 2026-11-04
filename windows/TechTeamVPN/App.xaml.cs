using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace TechTeamVPN;

public partial class App : Application
{
    private const string MutexName = @"Local\TechTeamVPN.SingleInstance";
    private const string ShowEventName = @"Local\TechTeamVPN.Show";
    private Mutex? mutex;

    public static readonly string CrashLog = Path.Combine(Store.LocalDir, "crash.log");

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // Any error at start used to close the app without a word; now it is shown and kept in crash.log.
        DispatcherUnhandledException += (_, ex) =>
        {
            Report(ex.Exception);
            ex.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, ex) => Report(ex.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, ex) =>
        {
            Log(ex.Exception);
            ex.SetObserved();
        };

        // The window is WebView2 (Edge): without its runtime nothing can be shown.
        string? webView;
        try { webView = CoreWebView2Environment.GetAvailableBrowserVersionString(); }
        catch (Exception) { webView = null; }
        if (string.IsNullOrEmpty(webView))
        {
            var answer = MessageBox.Show(
                "Для окна EthicVPN нужен компонент Microsoft Edge WebView2, а его нет на этом компьютере.\n\nОткрыть страницу загрузки? После установки запустите EthicVPN снова.",
                "EthicVPN", MessageBoxButton.OKCancel, MessageBoxImage.Warning);
            if (answer == MessageBoxResult.OK)
                Process.Start(new ProcessStartInfo("https://go.microsoft.com/fwlink/p/?LinkId=2124703") { UseShellExecute = true });
            Shutdown();
            return;
        }

        // One copy at a time; a second launch brings the first one forward. An elevated restart
        // waits a little for the old copy to exit.
        mutex = new Mutex(false, MutexName);
        var owned = false;
        try { owned = mutex.WaitOne(e.Args.Contains("--elevated") ? TimeSpan.FromSeconds(10) : TimeSpan.Zero); }
        catch (AbandonedMutexException) { owned = true; }
        if (!owned)
        {
            if (EventWaitHandle.TryOpenExisting(ShowEventName, out var show)) show.Set();
            Shutdown();
            return;
        }

        var window = new MainWindow(startMinimized: e.Args.Contains("--minimized"), connectNow: e.Args.Contains("--connect"));
        var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        new Thread(() =>
        {
            while (showEvent.WaitOne()) Dispatcher.Invoke(window.ShowFromTray);
        }) { IsBackground = true }.Start();
        if (!e.Args.Contains("--minimized")) window.Show();
    }

    private static void Log(Exception? e)
    {
        try
        {
            Directory.CreateDirectory(Store.LocalDir);
            File.AppendAllText(CrashLog, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {Bridge.Version}\n{e}\n\n");
        }
        catch (Exception) { }
    }

    private static void Report(Exception? e)
    {
        Log(e);
        MessageBox.Show($"Ошибка EthicVPN:\n\n{e?.Message}\n\nПодробности: {CrashLog}", "EthicVPN",
            MessageBoxButton.OK, MessageBoxImage.Error);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { mutex?.ReleaseMutex(); } catch (ApplicationException) { }
        base.OnExit(e);
    }
}
