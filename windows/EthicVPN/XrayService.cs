using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using EthicVPN.Core;

namespace EthicVPN;

public enum VpnStatus { Disconnected, Connecting, Connected, Error }

/// <summary>Runs xray.exe with the config for the selected server and reads its traffic counters.</summary>
public sealed class XrayService
{
    public static readonly string AppDir = AppContext.BaseDirectory;
    private static readonly string ConfigPath = Path.Combine(Store.LocalDir, "config.json");
    public static readonly string LogPath = Path.Combine(Store.LocalDir, "xray.log");
    private static readonly string AccessLogPath = Path.Combine(Store.LocalDir, "access.log");

    private readonly HttpClient metrics = new(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(2) };
    private Process? process;
    private bool stopping;
    private XrayOptions options = new();
    private Timer? trafficTimer;
    private long lastBytes, sessionBytes, lastDown, lastUp;
    private DateTime lastSample;
    private int ticks;

    public VpnStatus Status { get; private set; } = VpnStatus.Disconnected;
    public string? Error { get; private set; }
    public DateTime Since { get; private set; }
    public TunnelMode Mode => options.Mode;
    public int HttpPort => options.HttpPort;

    /// <summary>Status changed; the argument is true when xray stopped on its own.</summary>
    public event Action<bool>? StatusChanged;
    /// <summary>Bytes per second down and up, session total, and ads blocked since the last report.</summary>
    public event Action<double, double, long, int>? Traffic;

    /// <summary>A copy left over from a crash would hold the ports and the TUN adapter.</summary>
    public static void KillLeftovers()
    {
        foreach (var p in Process.GetProcessesByName("xray"))
        {
            try
            {
                if (string.Equals(Path.GetDirectoryName(p.MainModule?.FileName), Path.TrimEndingDirectorySeparator(AppDir), StringComparison.OrdinalIgnoreCase))
                    p.Kill(true);
            }
            catch (Exception) { }
        }
    }

    public async Task StartAsync(ServerProfile profile, XrayOptions o)
    {
        Stop();
        stopping = false;
        options = o with
        {
            AssetDir = AppDir,
            AccessLogPath = o.BlockAds ? AccessLogPath : null,
            // Warnings and errors go to stdout, which is copied into xray.log below.
            LogPath = null,
        };
        Error = null;
        SetStatus(VpnStatus.Connecting);
        Directory.CreateDirectory(Store.LocalDir);
        File.WriteAllText(ConfigPath, XrayConfig.Build(profile, options));
        File.WriteAllText(LogPath, $"EthicVPN: {profile.Summary}, mode {options.Mode}\n");
        if (options.AccessLogPath is not null) File.WriteAllText(AccessLogPath, "");

        var exe = Path.Combine(AppDir, "xray.exe");
        if (!File.Exists(exe))
        {
            Fail("Не найден xray.exe рядом с приложением");
            return;
        }
        var p = new Process
        {
            StartInfo = new ProcessStartInfo(exe, $"run -c \"{ConfigPath}\"")
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = AppDir,
            },
            EnableRaisingEvents = true,
        };
        p.OutputDataReceived += (_, e) => AppendLog(e.Data);
        p.ErrorDataReceived += (_, e) => AppendLog(e.Data);
        p.Exited += (_, _) => OnExited(p);
        try
        {
            p.Start();
        }
        catch (Exception e)
        {
            Fail(e.Message);
            return;
        }
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        process = p;

        // Connected once the metrics listener answers: the config loaded and the inbounds are up.
        for (var i = 0; i < 40 && !p.HasExited; i++)
        {
            await Task.Delay(250);
            try
            {
                await metrics.GetStringAsync($"http://127.0.0.1:{options.MetricsPort}/debug/vars");
                break;
            }
            catch (Exception) { }
        }
        if (p.HasExited || process != p) return;
        Since = DateTime.UtcNow;
        sessionBytes = 0;
        lastBytes = -1;
        SetStatus(VpnStatus.Connected);
        trafficTimer = new Timer(_ => _ = SampleAsync(), null, 1000, 1000);
    }

    public void Stop()
    {
        stopping = true;
        trafficTimer?.Dispose();
        trafficTimer = null;
        var p = process;
        process = null;
        if (p is not null)
        {
            try { if (!p.HasExited) p.Kill(true); p.WaitForExit(3000); } catch (Exception) { }
        }
        if (Status != VpnStatus.Disconnected) SetStatus(VpnStatus.Disconnected);
    }

    private void OnExited(Process p)
    {
        if (stopping || process != p) return;
        process = null;
        trafficTimer?.Dispose();
        var reason = LastLogLines();
        Error = string.IsNullOrWhiteSpace(reason) ? "Xray остановился" : reason;
        Status = VpnStatus.Error;
        StatusChanged?.Invoke(true);
    }

    private void Fail(string message)
    {
        Error = message;
        SetStatus(VpnStatus.Error);
    }

    private void SetStatus(VpnStatus status)
    {
        Status = status;
        StatusChanged?.Invoke(false);
    }

    private async Task SampleAsync()
    {
        try
        {
            var json = await metrics.GetStringAsync($"http://127.0.0.1:{options.MetricsPort}/debug/vars");
            using var doc = JsonDocument.Parse(json);
            long down = 0, up = 0;
            if (doc.RootElement.TryGetProperty("stats", out var stats) && stats.TryGetProperty("outbound", out var outbound))
            {
                // Proxied and direct traffic both pass through Xray; DNS answers and the blackhole don't count.
                foreach (var tag in new[] { "proxy", "direct" })
                {
                    if (!outbound.TryGetProperty(tag, out var o)) continue;
                    down += o.GetProperty("downlink").GetInt64();
                    up += o.GetProperty("uplink").GetInt64();
                }
            }
            var now = DateTime.UtcNow;
            double rateDown = 0, rateUp = 0;
            if (lastBytes >= 0)
            {
                var seconds = Math.Max(0.2, (now - lastSample).TotalSeconds);
                rateDown = Math.Max(0, down - lastDown) / seconds;
                rateUp = Math.Max(0, up - lastUp) / seconds;
                sessionBytes += Math.Max(0, down + up - lastBytes);
            }
            lastDown = down;
            lastUp = up;
            lastBytes = down + up;
            lastSample = now;
            var blocked = ++ticks % 5 == 0 ? CountBlocked() : 0;
            Traffic?.Invoke(rateDown, rateUp, sessionBytes, blocked);
        }
        catch (Exception) { }
    }

    /// <summary>Reads and empties the access log; Xray appends to it, so nothing that matters is lost.</summary>
    private int CountBlocked()
    {
        if (options.AccessLogPath is null) return 0;
        try
        {
            using var f = new FileStream(options.AccessLogPath, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete);
            using var reader = new StreamReader(f, Encoding.UTF8, false, 4096, leaveOpen: true);
            var text = reader.ReadToEnd();
            f.SetLength(0);
            return XrayConfig.CountBlocked(text);
        }
        catch (IOException)
        {
            return 0;
        }
    }

    private readonly object logLock = new();
    private void AppendLog(string? line)
    {
        if (line is null) return;
        lock (logLock)
        {
            try { File.AppendAllText(LogPath, line + "\n"); } catch (IOException) { }
        }
    }

    private static string LastLogLines()
    {
        try
        {
            var lines = File.ReadAllLines(LogPath).Where(l => l.Contains("error", StringComparison.OrdinalIgnoreCase) || l.Contains("failed")).ToList();
            return string.Join("\n", lines.TakeLast(2));
        }
        catch (IOException)
        {
            return "";
        }
    }
}
