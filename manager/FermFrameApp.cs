// FermFrame - Windows app to look after FermFrame clocks.
//  - Finds every FermFrame clock on the home network and shows its brew, graph and health
//  - Changes a clock's settings (same as its web page)
//  - Updates a clock's firmware (the firmware files are built into this .exe)
//  - Sets up brand-new clocks (same steps as the old PowerShell installer)
//
// Built with the C# 5 compiler that ships with Windows (see build.ps1), so keep to C# 5.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("FermFrame")]
[assembly: AssemblyProduct("FermFrame")]
[assembly: AssemblyDescription("Look after your FermFrames")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]
[assembly: AssemblyInformationalVersion("1.0.0")]
[assembly: AssemblyCopyright("MIT licence - see LICENSE")]

namespace FermFrame
{
    static class AppInfo
    {
        public const string Version = "1.0.0";   // keep in step with the assembly versions above
    }

    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            try { Native.SetProcessDPIAware(); } catch { }   // draw sharp at 125%/150%; UI.S does the scaling
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.ThreadException += (s, e) => MessageBox.Show("Something went wrong:\n\n" + e.Exception.Message, "FermFrame", MessageBoxButtons.OK, MessageBoxIcon.Error);

            var a = args.Select(x => x.ToLowerInvariant()).ToList();
            if (a.Contains("/uninstall")) { Setup.Uninstall(a.Contains("/quiet")); return; }
            if (a.Contains("/install"))   // for scripted installs: /install [/nodesktop] [/quiet]
            {
                bool quiet = a.Contains("/quiet");
                try
                {
                    if (!Setup.CloseRunningCopies()) throw new Exception("FermFrame is open. Close it and try again.");
                    Setup.Install(!a.Contains("/nodesktop"));
                    if (!quiet) Process.Start(new ProcessStartInfo(Setup.Exe) { WorkingDirectory = Setup.Dir });
                }
                catch (Exception ex)
                {
                    if (!quiet) MessageBox.Show("Couldn't install FermFrame:\n\n" + ex.Message, "FermFrame", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    Environment.ExitCode = 1;
                }
                return;
            }

            // Downloaded copy: offer to install (or update) it, unless asked to just run
            if (!Setup.RunningInstalled && !a.Contains("/portable"))
            {
                using (var f = new InstallForm())
                {
                    Application.Run(f);
                    if (!f.RunHere) return;
                }
            }
            Application.Run(new MainForm());
        }
    }

    // ------------------------------------------------------------------ installing on this PC (just for this user, no admin)

    static class Setup
    {
        public static string Dir { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "FermFrame"); } }
        public static string Exe { get { return Path.Combine(Dir, "FermFrame.exe"); } }
        static string StartMenuLink { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "FermFrame.lnk"); } }
        static string DesktopLink { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "FermFrame.lnk"); } }
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\FermFrame";

        // This program's own file (not the host's, if something else loaded it)
        static string Self { get { return Assembly.GetExecutingAssembly().Location; } }

        public static bool RunningInstalled
        {
            get { return string.Equals(Path.GetFullPath(Self), Path.GetFullPath(Exe), StringComparison.OrdinalIgnoreCase); }
        }

        // Version of the installed copy, or null
        public static string InstalledVersion
        {
            get
            {
                try { return File.Exists(Exe) ? FileVersionInfo.GetVersionInfo(Exe).ProductVersion : null; } catch { return null; }
            }
        }

        static List<Process> RunningCopies()
        {
            var list = new List<Process>();
            foreach (var p in Process.GetProcessesByName("FermFrame"))
            {
                if (p.Id == Process.GetCurrentProcess().Id) continue;
                try { if (string.Equals(p.MainModule.FileName, Exe, StringComparison.OrdinalIgnoreCase)) list.Add(p); } catch { }
            }
            return list;
        }

        // Asks the installed copy to close (so it can be replaced). True when none is left running.
        public static bool CloseRunningCopies()
        {
            foreach (var p in RunningCopies()) { try { p.CloseMainWindow(); p.WaitForExit(5000); } catch { } }
            return RunningCopies().Count == 0;
        }

        public static void Install(bool desktop)
        {
            Directory.CreateDirectory(Dir);
            // Copy the bytes (not the file) so the installed copy is a plain local program
            File.WriteAllBytes(Exe, File.ReadAllBytes(Self));
            Shortcut(StartMenuLink);
            if (desktop) Shortcut(DesktopLink); else TryDelete(DesktopLink);

            using (var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", "FermFrame");
                k.SetValue("DisplayVersion", AppInfo.Version);
                k.SetValue("Publisher", "FermFrame (open source)");
                k.SetValue("DisplayIcon", Exe + ",0");
                k.SetValue("InstallLocation", Dir);
                k.SetValue("UninstallString", "\"" + Exe + "\" /uninstall");
                k.SetValue("QuietUninstallString", "\"" + Exe + "\" /uninstall /quiet");
                k.SetValue("NoModify", 1, Microsoft.Win32.RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, Microsoft.Win32.RegistryValueKind.DWord);
                k.SetValue("EstimatedSize", (int)(new FileInfo(Exe).Length / 1024), Microsoft.Win32.RegistryValueKind.DWord);
                if (Bundled.ProjectUrl != "") k.SetValue("URLInfoAbout", Bundled.ProjectUrl);
            }
        }

        static void Shortcut(string path)
        {
            var t = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(t);
            try
            {
                object link = t.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { path });
                var lt = link.GetType();
                lt.InvokeMember("TargetPath", BindingFlags.SetProperty, null, link, new object[] { Exe });
                lt.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, link, new object[] { Dir });
                lt.InvokeMember("IconLocation", BindingFlags.SetProperty, null, link, new object[] { Exe + ",0" });
                lt.InvokeMember("Description", BindingFlags.SetProperty, null, link, new object[] { "Look after your FermFrames" });
                lt.InvokeMember("Save", BindingFlags.InvokeMethod, null, link, null);
                Marshal.ReleaseComObject(link);
            }
            finally { Marshal.ReleaseComObject(shell); }
        }

        static void TryDelete(string f) { try { if (File.Exists(f)) File.Delete(f); } catch { } }

        public static void Uninstall(bool quiet)
        {
            bool forget = false;
            if (!quiet)
            {
                if (MessageBox.Show("Remove FermFrame from this PC?\n\nYour FermFrames keep working - this only removes the app.", "Uninstall FermFrame",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) != DialogResult.Yes) return;
                forget = MessageBox.Show("Also forget the names you gave your FermFrames on this PC?", "Uninstall FermFrame",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) == DialogResult.Yes;
            }
            if (!CloseRunningCopies() && !quiet)
                MessageBox.Show("FermFrame is still open. Close it, then uninstall again.", "Uninstall FermFrame", MessageBoxButtons.OK, MessageBoxIcon.Warning);

            TryDelete(StartMenuLink);
            TryDelete(DesktopLink);
            try { Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); } catch { }
            if (forget) { try { Directory.Delete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FermFrame"), true); } catch { } }

            // This program can't delete itself while running: a hidden helper removes the folder a moment after it closes
            if (Directory.Exists(Dir))
            {
                try
                {
                    var psi = new ProcessStartInfo("cmd.exe", "/c timeout /t 3 /nobreak >nul & rmdir /s /q \"" + Dir + "\"")
                    { CreateNoWindow = true, UseShellExecute = false, WorkingDirectory = Path.GetTempPath() };
                    Process.Start(psi);
                }
                catch { }
            }
            if (!quiet) MessageBox.Show("FermFrame has been removed.", "Uninstall FermFrame", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    // The window a downloaded copy shows first: install, update or just run
    class InstallForm : DarkForm
    {
        public bool RunHere;
        Label status, foot;
        Button go;
        CheckBox desktop;

        public InstallForm()
        {
            Text = "FermFrame";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            ClientSize = new Size(520, 420);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;

            string have = Setup.InstalledVersion;
            bool newer = have != null && Firmware.Older(have, AppInfo.Version);
            bool same = have != null && !newer;

            var logo = UI.Lbl("FermFrame", 24, true, UI.Amber); logo.Location = new Point(28, 24);
            var sub = UI.Lbl("Fermentation readings from Brewfather or RAPT on a small desk display.", 10.5f, false, UI.Dim);
            sub.Location = new Point(30, 76); sub.MaximumSize = new Size(460, 0);
            var card = new Card { Location = new Point(28, 124), Size = new Size(464, 150) };
            var what = UI.Lbl(have == null ? "Install FermFrame " + AppInfo.Version
                : newer ? "Update FermFrame " + have + " to " + AppInfo.Version
                : "FermFrame " + have + " is already installed", 12.5f, true);
            what.Location = new Point(20, 18);
            var info = UI.Lbl(have == null || newer
                ? "Installs just for you - no admin rights needed.\nRemove it any time in Windows Settings > Apps."
                : "Open the installed copy, or install this one over it.", 9.5f, false, UI.Dim);
            info.Location = new Point(20, 50);
            desktop = UI.Check("Put a shortcut on the desktop"); desktop.Checked = true; desktop.Location = new Point(18, 100);
            card.Controls.AddRange(new Control[] { what, info, desktop });

            go = UI.Btn(have == null ? "Install" : newer ? "Update" : "Open FermFrame", true);
            go.AutoSize = false; go.Size = new Size(170, 42); go.Location = new Point(28, 294);
            go.Click += (s, e) => { if (same) Launch(); else DoInstall(); };
            var other = UI.Btn(same ? "Reinstall" : "Just run it this time"); other.AutoSize = false; other.Size = new Size(170, 42); other.Location = new Point(208, 294);
            other.Click += (s, e) => { if (same) DoInstall(); else { RunHere = true; Close(); } };
            status = UI.Lbl("", 9.5f, false, UI.Dim); status.Location = new Point(30, 346); status.MaximumSize = new Size(460, 0);
            foot = UI.Lbl("Free, open-source software (MIT). Not made by or connected with GeekMagic, Brewfather or RAPT.", 8.5f, false, UI.Dim);
            foot.Location = new Point(30, 358); foot.MaximumSize = new Size(470, 0);
            Controls.AddRange(new Control[] { logo, sub, card, go, other, status, foot });
            FinishLayout();
        }

        void Launch()
        {
            try { Process.Start(new ProcessStartInfo(Setup.Exe) { WorkingDirectory = Setup.Dir }); } catch (Exception ex) { Say(ex.Message, true); return; }
            Close();
        }

        void Say(string text, bool bad)
        {
            foot.Visible = false;
            status.Text = text; status.ForeColor = bad ? UI.Bad : UI.Dim;
        }

        void DoInstall()
        {
            go.Enabled = false; Say("Installing...", false); Refresh();
            try
            {
                if (!Setup.CloseRunningCopies()) { Say("FermFrame is open. Close it, then press Install again.", true); go.Enabled = true; return; }
                Setup.Install(desktop.Checked);
            }
            catch (Exception ex) { Say("Couldn't install: " + ex.Message, true); go.Enabled = true; return; }
            Launch();
        }
    }

    // ------------------------------------------------------------------ Windows calls for the dark look

    static class Native
    {
        [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr h);
        [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr h, IntPtr dc);
        [DllImport("gdi32.dll")] static extern int GetDeviceCaps(IntPtr dc, int index);
        [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr h, int attr, ref int value, int size);
        [DllImport("uxtheme.dll", CharSet = CharSet.Unicode)] static extern int SetWindowTheme(IntPtr h, string app, string idList);

        public static int ScreenDpi()
        {
            try { var dc = GetDC(IntPtr.Zero); int d = GetDeviceCaps(dc, 88); ReleaseDC(IntPtr.Zero, dc); return d > 0 ? d : 96; } catch { return 96; }
        }

        static int Ref(Color c) { return c.R | (c.G << 8) | (c.B << 16); }

        // Dark title bar (Windows 10 20H1+ / 11), in the app's own colours on Windows 11
        public static void DarkTitle(IntPtr h)
        {
            try
            {
                int on = 1;
                if (DwmSetWindowAttribute(h, 20, ref on, 4) != 0) DwmSetWindowAttribute(h, 19, ref on, 4);
                int cap = Ref(UI.Bg); DwmSetWindowAttribute(h, 35, ref cap, 4);
                int border = Ref(UI.Line); DwmSetWindowAttribute(h, 34, ref border, 4);
                int text = Ref(UI.Text); DwmSetWindowAttribute(h, 36, ref text, 4);
            }
            catch { }
        }

        // Dark scroll bars on lists, scrolling panels and text boxes
        public static void DarkScroll(Control c)
        {
            EventHandler apply = (s, e) => { try { SetWindowTheme(c.Handle, "DarkMode_Explorer", null); } catch { } };
            if (c.IsHandleCreated) apply(c, EventArgs.Empty);
            c.HandleCreated += apply;
        }
    }

    // Every window: dark title bar, and scaled for the screen's text size
    class DarkForm : Form
    {
        public DarkForm()
        {
            AutoScaleMode = AutoScaleMode.None;
            BackColor = UI.Bg; ForeColor = UI.Text; Font = UI.F(10);
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.DarkTitle(Handle);
        }

        // Call at the end of the constructor, after all controls are added (sizes are written for 100%)
        protected void FinishLayout()
        {
            if (UI.S == 1f) return;
            var min = MinimumSize;
            MinimumSize = Size.Empty;
            Scale(new SizeF(UI.S, UI.S));
            if (!min.IsEmpty) MinimumSize = new Size(UI.Px(min.Width), UI.Px(min.Height));
        }
    }

    // ------------------------------------------------------------------ look

    static class UI
    {
        // Screen scaling: 1 at 100%, 1.5 at 150%. FERMFRAME_SCALE overrides it for testing.
        public static readonly float S = GetScale();
        static float GetScale()
        {
            float f;
            var env = Environment.GetEnvironmentVariable("FERMFRAME_SCALE");
            if (env != null && float.TryParse(env, NumberStyles.Float, CultureInfo.InvariantCulture, out f) && f >= 0.5f && f <= 4f) return f;
            return Native.ScreenDpi() / 96f;
        }
        public static int Px(float v) { return (int)Math.Round(v * S); }
        public static float Pf(float v) { return v * S; }

        public static GraphicsPath Round(RectangleF r, float radius)
        {
            var p = new GraphicsPath();
            float d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
            if (d <= 1) { p.AddRectangle(r); return p; }
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        public static Color Mix(Color a, Color b, float t)
        {
            return Color.FromArgb((int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
        }

        // The colour behind a control (its nearest parent that isn't see-through)
        public static Color Behind(Control c)
        {
            for (var p = c.Parent; p != null; p = p.Parent) if (p.BackColor.A == 255) return p.BackColor;
            return Bg;
        }

        public static readonly Color Bg = Color.FromArgb(20, 20, 20);
        public static readonly Color Side = Color.FromArgb(28, 28, 28);
        public static readonly Color Card = Color.FromArgb(36, 36, 36);
        public static readonly Color Field = Color.FromArgb(46, 46, 46);
        public static readonly Color Line = Color.FromArgb(58, 58, 58);
        public static readonly Color Amber = Color.FromArgb(255, 204, 51);
        public static readonly Color Blue = Color.FromArgb(91, 190, 255);
        public static readonly Color Text = Color.FromArgb(232, 232, 232);
        public static readonly Color Dim = Color.FromArgb(150, 150, 150);
        public static readonly Color Good = Color.FromArgb(110, 220, 120);
        public static readonly Color Bad = Color.FromArgb(255, 128, 110);
        public const string Deg = "°";
        public const string Dot = "  ·  ";

        // Sizes are in points at 100%; made in pixels so they grow with the screen scaling exactly like the layout
        public static Font F(float size, bool bold = false)
        {
            return new Font("Segoe UI", size * 96f / 72f * S, bold ? FontStyle.Bold : FontStyle.Regular, GraphicsUnit.Pixel);
        }

        public static Button Btn(string text, bool primary = false)
        {
            var b = new RButton();
            b.Text = text;
            b.Font = F(10, primary);
            b.Height = 36;
            b.AutoSize = true;
            b.AutoSizeMode = AutoSizeMode.GrowOnly;
            b.Padding = new Padding(12, 0, 12, 0);
            b.BackColor = primary ? Amber : Field;
            b.ForeColor = primary ? Color.Black : Text;
            return b;
        }

        public static Label Lbl(string text, float size = 10, bool bold = false, Color? color = null)
        {
            var l = new Label();
            l.Text = text; l.Font = F(size, bold); l.AutoSize = true; l.UseMnemonic = false;
            l.ForeColor = color.HasValue ? color.Value : Text;
            l.BackColor = Color.Transparent;
            return l;
        }

        public static TextBox Box(bool password = false)
        {
            var t = new TextBox();
            t.BackColor = Field; t.ForeColor = Color.White; t.BorderStyle = BorderStyle.FixedSingle;
            t.Font = F(10.5f);
            t.UseSystemPasswordChar = password;
            return t;
        }

        public static ComboBox Combo()
        {
            var c = new DarkCombo();
            c.DropDownStyle = ComboBoxStyle.DropDownList;
            c.Font = F(10.5f);
            return c;
        }

        public static CheckBox Check(string text)
        {
            return new DarkCheck { Text = text, AutoSize = true, ForeColor = Text, Font = F(10.5f) };
        }

        public static RadioButton Radio(string text)
        {
            return new DarkRadio { Text = text, AutoSize = true, ForeColor = Text, Font = F(10.5f) };
        }

        // A thin line between sections
        public static Panel Rule(int width) { return new Panel { Size = new Size(width, 1), BackColor = Line }; }

        public static string Esc(object o) { return o == null ? "" : WebUtility.HtmlDecode(o.ToString()); }

        public static double? Num(object o)
        {
            if (o == null) return null;
            try { return Convert.ToDouble(o, CultureInfo.InvariantCulture); } catch { return null; }
        }

        public static string Str(Dictionary<string, object> d, string k)
        {
            object o; return d != null && d.TryGetValue(k, out o) && o != null ? Esc(o) : "";
        }
    }

    class Opt
    {
        public string Value, Text;
        public Opt(string v, string t) { Value = v; Text = t; }
        public override string ToString() { return Text; }

        public static void Fill(ComboBox c, params string[] pairs)
        {
            c.Items.Clear();
            for (int i = 0; i + 1 < pairs.Length; i += 2) c.Items.Add(new Opt(pairs[i], pairs[i + 1]));
        }
        public static void Select(ComboBox c, string value)
        {
            foreach (Opt o in c.Items) if (o.Value == value) { c.SelectedItem = o; return; }
            if (!string.IsNullOrEmpty(value)) { var o = new Opt(value, value); c.Items.Add(o); c.SelectedItem = o; }
            else if (c.Items.Count > 0) c.SelectedIndex = 0;
        }
        public static string Of(ComboBox c) { var o = c.SelectedItem as Opt; return o == null ? "" : o.Value; }
    }

    // ------------------------------------------------------------------ dark controls

    // Rounded button that lightens on hover. Swatch = a colour button with no text.
    class RButton : Button
    {
        bool hover, down;
        public bool Swatch;

        public RButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            FlatStyle = FlatStyle.Flat; FlatAppearance.BorderSize = 0; UseVisualStyleBackColor = false; Cursor = Cursors.Hand;
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            var behind = UI.Behind(this);
            g.Clear(behind);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var fill = BackColor;
            if (!Enabled) fill = UI.Mix(fill, behind, 0.55f);
            else if (down) fill = UI.Mix(fill, Color.Black, 0.12f);
            else if (hover) fill = UI.Mix(fill, Color.White, Swatch ? 0f : 0.10f);
            var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (var path = UI.Round(r, UI.Pf(7)))
            {
                using (var b = new SolidBrush(fill)) g.FillPath(b, path);
                bool outline = Swatch || BackColor == UI.Field;
                if (outline || (Focused && ShowFocusCues))
                    using (var p = new Pen(Focused && ShowFocusCues ? UI.Amber : hover && Swatch ? UI.Dim : UI.Line, Math.Max(1f, UI.Pf(1)))) g.DrawPath(p, path);
            }
            if (Swatch) return;
            var fore = Enabled ? ForeColor : UI.Mix(ForeColor, behind, 0.5f);
            TextRenderer.DrawText(g, Text, Font, ClientRectangle, fore,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
        }
    }

    // Drop-down list with a dark arrow and border (Windows draws the arrow light otherwise)
    class DarkCombo : ComboBox
    {
        public DarkCombo()
        {
            FlatStyle = FlatStyle.Flat; BackColor = UI.Field; ForeColor = Color.White;
        }

        protected override void WndProc(ref Message m)
        {
            base.WndProc(ref m);
            if (m.Msg != 0x000F || !IsHandleCreated) return;   // WM_PAINT
            using (var g = Graphics.FromHwnd(Handle))
            {
                int w = UI.Px(26);
                var arrow = new Rectangle(Width - w, 0, w, Height);
                using (var b = new SolidBrush(UI.Field)) g.FillRectangle(b, arrow);
                g.SmoothingMode = SmoothingMode.AntiAlias;
                float cx = arrow.X + arrow.Width / 2f, cy = Height / 2f, a = UI.Pf(4);
                using (var p = new Pen(Enabled ? UI.Dim : UI.Line, Math.Max(1.5f, UI.Pf(1.6f))))
                    g.DrawLines(p, new[] { new PointF(cx - a, cy - a / 2), new PointF(cx, cy + a / 2), new PointF(cx + a, cy - a / 2) });
                g.SmoothingMode = SmoothingMode.None;
                using (var p = new Pen(Focused ? UI.Amber : UI.Line)) g.DrawRectangle(p, 0, 0, Width - 1, Height - 1);
            }
        }
    }

    class DarkCheck : CheckBox
    {
        public DarkCheck()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
            Cursor = Cursors.Hand;
        }
        protected override void OnPaint(PaintEventArgs e) { Tick.Paint(this, e.Graphics, Checked, false, ShowFocusCues); }
        public override Size GetPreferredSize(Size p) { return Tick.Size(this); }
    }

    class DarkRadio : RadioButton
    {
        public DarkRadio()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
            Cursor = Cursors.Hand;
        }
        protected override void OnPaint(PaintEventArgs e) { Tick.Paint(this, e.Graphics, Checked, true, ShowFocusCues); }
        public override Size GetPreferredSize(Size p) { return Tick.Size(this); }
    }

    static class Tick
    {
        // Box + gap + text, so AutoSize never cuts the text off
        public static Size Size(ButtonBase c)
        {
            var t = TextRenderer.MeasureText(c.Text, c.Font);
            return new Size(UI.Px(1) + UI.Px(17) + UI.Px(8) + t.Width + UI.Px(4), Math.Max(t.Height, UI.Px(17)) + UI.Px(6));
        }

        public static void Paint(ButtonBase c, Graphics g, bool on, bool round, bool cue)
        {
            g.Clear(UI.Behind(c));
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float s = UI.Pf(17), y = (c.Height - s) / 2f;
            var box = new RectangleF(UI.Pf(1), y, s, s);
            var edge = !c.Enabled ? UI.Line : on ? UI.Amber : UI.Dim;
            if (round)
            {
                using (var p = new Pen(edge, UI.Pf(1.5f))) g.DrawEllipse(p, box);
                if (on) using (var b = new SolidBrush(c.Enabled ? UI.Amber : UI.Line)) { float i = s * 0.27f; g.FillEllipse(b, box.X + i, box.Y + i, s - 2 * i, s - 2 * i); }
            }
            else
            {
                using (var path = UI.Round(box, UI.Pf(4)))
                {
                    if (on) using (var b = new SolidBrush(c.Enabled ? UI.Amber : UI.Line)) g.FillPath(b, path);
                    else using (var p = new Pen(edge, UI.Pf(1.5f))) g.DrawPath(p, path);
                }
                if (on)
                    using (var p = new Pen(Color.Black, UI.Pf(2.2f)) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round })
                        g.DrawLines(p, new[] { new PointF(box.X + s * 0.24f, box.Y + s * 0.52f), new PointF(box.X + s * 0.43f, box.Y + s * 0.71f), new PointF(box.X + s * 0.77f, box.Y + s * 0.30f) });
            }
            if (c.Focused && cue) using (var p = new Pen(UI.Amber)) g.DrawRectangle(p, 0, 0, c.Width - 1, c.Height - 1);
            var tx = new Rectangle((int)(box.Right + UI.Pf(8)), 0, c.Width - (int)(box.Right + UI.Pf(8)), c.Height);
            TextRenderer.DrawText(g, c.Text, c.Font, tx, c.Enabled ? c.ForeColor : UI.Dim, TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.SingleLine);
        }
    }

    // Brightness slider in the app's colours (replaces the light Windows one)
    class Slider : Control
    {
        int min = 0, max = 100, val;
        public event EventHandler ValueChanged;
        public int Minimum { get { return min; } set { min = value; Value = val; } }
        public int Maximum { get { return max; } set { max = value; Value = val; } }
        public int Step = 5;
        public int Value
        {
            get { return val; }
            set { int v = Math.Max(min, Math.Min(max, value)); if (v == val) return; val = v; Invalidate(); if (ValueChanged != null) ValueChanged(this, EventArgs.Empty); }
        }

        public Slider()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.Selectable, true);
            TabStop = true; Cursor = Cursors.Hand; Height = 32;
        }

        float R { get { return UI.Pf(9); } }
        void SetFromX(int x) { float f = (x - R) / Math.Max(1f, Width - 2 * R); Value = min + (int)Math.Round(f * (max - min) / Step) * Step; }

        protected override void OnMouseDown(MouseEventArgs e) { Focus(); if (e.Button == MouseButtons.Left) { Capture = true; SetFromX(e.X); } base.OnMouseDown(e); }
        protected override void OnMouseMove(MouseEventArgs e) { if (Capture && e.Button == MouseButtons.Left) SetFromX(e.X); base.OnMouseMove(e); }
        protected override void OnMouseUp(MouseEventArgs e) { Capture = false; base.OnMouseUp(e); }
        protected override bool IsInputKey(Keys k) { return k == Keys.Left || k == Keys.Right || base.IsInputKey(k); }
        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Left || e.KeyCode == Keys.Down) Value -= Step;
            else if (e.KeyCode == Keys.Right || e.KeyCode == Keys.Up) Value += Step;
            else if (e.KeyCode == Keys.Home) Value = min;
            else if (e.KeyCode == Keys.End) Value = max;
            base.OnKeyDown(e);
        }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }
        protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(UI.Behind(this));
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float r = R, cy = Height / 2f, x0 = r, x1 = Width - r, h = UI.Pf(5);
            float x = x0 + (x1 - x0) * (max == min ? 0 : (val - min) / (float)(max - min));
            var accent = Enabled ? UI.Amber : UI.Dim;
            using (var p = UI.Round(new RectangleF(x0, cy - h / 2, x1 - x0, h), h / 2)) using (var b = new SolidBrush(UI.Field)) g.FillPath(b, p);
            if (x > x0) using (var p = UI.Round(new RectangleF(x0, cy - h / 2, x - x0, h), h / 2)) using (var b = new SolidBrush(accent)) g.FillPath(b, p);
            using (var b = new SolidBrush(accent)) g.FillEllipse(b, x - r, cy - r, 2 * r, 2 * r);
            if (Focused) using (var p = new Pen(Color.White, UI.Pf(1.5f))) g.DrawEllipse(p, x - r + 1, cy - r + 1, 2 * r - 2, 2 * r - 2);
        }
    }

    // A thin amber bar that moves while something is working
    class BusyBar : Control
    {
        readonly System.Windows.Forms.Timer t = new System.Windows.Forms.Timer { Interval = 30 };
        float pos;
        public BusyBar()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
            t.Tick += (s, e) => { pos = (pos + 0.012f) % 1.4f; Invalidate(); };
        }
        protected override void OnVisibleChanged(EventArgs e) { t.Enabled = Visible; base.OnVisibleChanged(e); }
        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics; g.Clear(UI.Behind(this)); g.SmoothingMode = SmoothingMode.AntiAlias;
            float h = Height, r = h / 2;
            using (var p = UI.Round(new RectangleF(0, 0, Width - 1, h - 1), r)) using (var b = new SolidBrush(UI.Field)) g.FillPath(b, p);
            float w = Width * 0.3f, x = (pos - 0.3f) * Width;
            var seg = RectangleF.Intersect(new RectangleF(x, 0, w, h - 1), new RectangleF(0, 0, Width - 1, h - 1));
            if (seg.Width > 1) using (var p = UI.Round(seg, r)) using (var b = new SolidBrush(UI.Amber)) g.FillPath(b, p);
        }
        protected override void Dispose(bool disposing) { if (disposing) t.Dispose(); base.Dispose(disposing); }
    }

    // ------------------------------------------------------------------ the firmware built into this .exe

    static class Firmware
    {
        public static byte[] Full { get { return Read("fermframe.bin"); } }
        public static byte[] Lite { get { return Read("fermframe-lite.bin"); } }
        public const int StockMax = 507904;   // largest file a brand-new clock's own update page takes

        static byte[] Read(string name)
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (s == null) throw new Exception("The firmware file " + name + " is missing from this app.");
                var ms = new MemoryStream(); s.CopyTo(ms); return ms.ToArray();
            }
        }

        // What a .bin file is, before it goes near a FermFrame
        public class Check
        {
            public string Block;          // set = never install this (wrong chip, not firmware, too big)
            public bool IsFermFrame;
            public string Version = "", Build = "";
        }

        const int MaxSketch = 1044464;  // largest program the FermFrame's flash layout (4M1M) can hold

        static int Find(byte[] b, string s)
        {
            var k = Encoding.ASCII.GetBytes(s);
            for (int i = 0; i + k.Length <= b.Length; i++)
            {
                int j = 0;
                while (j < k.Length && b[i + j] == k[j]) j++;
                if (j == k.Length) return i;
            }
            return -1;
        }

        public static Check Inspect(byte[] b)
        {
            var c = new Check();
            // An ESP8266 Arduino program starts with header 0xE9 and its first part loads at 0x4010xxxx.
            // ESP32 programs have 0xE9 too, but an extended header where this address would be.
            if (b.Length < 4096 || b[0] != 0xE9) { c.Block = "This isn't a firmware file."; return c; }
            bool esp8266 = b[11] == 0x40 && b[10] == 0x10 && Find(b, "esp8266") >= 0;
            if (!esp8266) { c.Block = "This firmware is for a different chip (not the ESP8266 inside a FermFrame). Installing it would stop the FermFrame working until it's opened up and repaired with a cable."; return c; }
            if (b.Length > MaxSketch) { c.Block = "This file is too big to fit on a FermFrame (" + (b.Length / 1024) + " KB)."; return c; }
            c.IsFermFrame = Find(b, "\"firmware\":\"fermframe\"") >= 0 || Find(b, "\"firmware\":\"brewscreen\"") >= 0;
            var text = Encoding.ASCII.GetString(b);
            var m = Regex.Match(text, "(?:FermFrame|BrewScreen) ([0-9][0-9.]*) \\((full|lite)\\)");
            if (m.Success) { c.Version = m.Groups[1].Value; c.Build = m.Groups[2].Value; }
            return c;
        }

        public static bool IsOlder(string clockVersion) { return Older(clockVersion, Bundled.Version); }

        public static bool Older(string a, string b)
        {
            try { return new Version(a) < new Version(b); } catch { return false; }
        }
    }

    // ------------------------------------------------------------------ talking to clocks

    class Found
    {
        public string Kind;   // "fermframe" or "stock" (a new Geekmagic clock)
        public string Ip, Title;
        public Dictionary<string, object> Info;
        public string Base { get { return "http://" + Ip; } }
    }

    class Resp { public int Status; public string Body = ""; public string Error; }

    static class Net
    {
        static readonly HttpClient http;
        static readonly JavaScriptSerializer json = new JavaScriptSerializer();

        static Net()
        {
            var h = new HttpClientHandler(); h.UseProxy = false;
            http = new HttpClient(h); http.Timeout = TimeSpan.FromMinutes(4);
        }

        static string Why(Exception e)
        {
            while (e.InnerException != null) e = e.InnerException;
            return e is TaskCanceledException ? "timed out" : e.Message;
        }

        static Resp Send(Func<CancellationToken, Task<HttpResponseMessage>> go, int seconds)
        {
            try
            {
                using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(seconds)))
                {
                    var r = go(cts.Token).Result;
                    return new Resp { Status = (int)r.StatusCode, Body = r.Content.ReadAsStringAsync().Result };
                }
            }
            catch (Exception e) { return new Resp { Status = 0, Error = Why(e) }; }
        }

        public static Resp Get(string url, int seconds)
        {
            return Send(t => http.GetAsync(url, t), seconds);
        }

        public static Resp PostForm(string url, Dictionary<string, string> fields, int seconds)
        {
            return Send(t => http.PostAsync(url, new FormUrlEncodedContent(fields), t), seconds);
        }

        public static Resp PostFile(string url, string field, string fileName, byte[] bytes, int seconds)
        {
            var mp = new MultipartFormDataContent();
            var fc = new ByteArrayContent(bytes);
            fc.Headers.ContentType = System.Net.Http.Headers.MediaTypeHeaderValue.Parse("application/octet-stream");
            mp.Add(fc, field, fileName);
            return Send(t => http.PostAsync(url, mp, t), seconds);
        }

        public static Dictionary<string, object> Json(string s)
        {
            try { return json.DeserializeObject(s) as Dictionary<string, object>; } catch { return null; }
        }

        public static string ToJson(object o) { return json.Serialize(o); }
        public static T FromJson<T>(string s) { return json.Deserialize<T>(s); }

        // A clock's status, from FermFrame (/fermframe.json) or the older BrewScreen (/brewscreen.json)
        public static Dictionary<string, object> ClockInfo(string baseUrl, int seconds)
        {
            foreach (var path in new[] { "/fermframe.json", "/brewscreen.json" })
            {
                var r = Get(baseUrl + path, seconds);
                if (r.Status != 200) { if (r.Status == 0) return null; continue; }
                var j = Json(r.Body);
                var fw = UI.Str(j, "firmware");
                if (fw == "fermframe" || fw == "brewscreen") return j;
            }
            return null;
        }

        public static string TitleOf(string body)
        {
            return Regex.Match(body ?? "", "(?i)<title>([^<]*)</title>").Groups[1].Value.Trim();
        }

        public static bool IsStockTitle(string t) { return Regex.IsMatch(t ?? "", "(?i)smart weather clock|스마트"); }

        // The first three parts of every IPv4 address this PC has on a real network (e.g. "192.168.1")
        public static List<string> LocalPrefixes()
        {
            var list = new List<string>();
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback || ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                var props = ni.GetIPProperties();
                if (props.GatewayAddresses.Count == 0) continue;
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    var ip = ua.Address.ToString();
                    if (ip.StartsWith("169.254.")) continue;
                    var p = string.Join(".", ip.Split('.').Take(3));
                    if (!list.Contains(p)) list.Add(p);
                }
            }
            return list;
        }

        // Looks at every address on the home network for clocks
        public static List<Found> Scan(bool includeStock)
        {
            var ips = new List<string>();
            foreach (var p in LocalPrefixes()) for (int n = 1; n < 255; n++) ips.Add(p + "." + n);

            var clients = new TcpClient[ips.Count];
            var tasks = new Task[ips.Count];
            for (int i = 0; i < ips.Count; i++)
            {
                clients[i] = new TcpClient();
                try { tasks[i] = clients[i].ConnectAsync(IPAddress.Parse(ips[i]), 80); }
                catch { tasks[i] = Task.FromResult(0); }
            }
            try { Task.WaitAll(tasks, 2500); } catch { }
            var open = new List<string>();
            for (int i = 0; i < ips.Count; i++)
            {
                try { if (tasks[i].Status == TaskStatus.RanToCompletion && clients[i].Connected) open.Add(ips[i]); } catch { }
                try { clients[i].Close(); } catch { }
            }

            var found = new List<Found>();
            var lk = new object();
            Parallel.ForEach(open, new ParallelOptions { MaxDegreeOfParallelism = 16 }, ip =>
            {
                var j = ClockInfo("http://" + ip, 4);
                if (j != null) { lock (lk) found.Add(new Found { Kind = "fermframe", Ip = ip, Info = j }); return; }
                if (!includeStock) return;
                var r = Get("http://" + ip + "/", 4);
                var t = TitleOf(r.Body);
                if (r.Status == 200 && IsStockTitle(t)) lock (lk) found.Add(new Found { Kind = "stock", Ip = ip, Title = t });
            });
            return found.OrderBy(f => f.Ip.Length).ThenBy(f => f.Ip).ToList();
        }
    }

    // ------------------------------------------------------------------ a clock and the list of them

    class Clock
    {
        public string Mac = "", Name = "", Ip = "";
        [ScriptIgnore] public Dictionary<string, object> Info, Graph;
        [ScriptIgnore] public ClockSettings Settings;
        [ScriptIgnore] public bool Online, Busy;
        [ScriptIgnore] public DateTime Checked;

        [ScriptIgnore] public string Base { get { return "http://" + Ip; } }
        [ScriptIgnore] public string Version { get { return UI.Str(Info, "version"); } }
        [ScriptIgnore] public string Build { get { return UI.Str(Info, "build"); } }
        [ScriptIgnore] public string Problem { get { return UI.Str(Info, "problem"); } }
    }

    static class Store
    {
        static string Dir { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FermFrame"); } }
        static string File_ { get { return Path.Combine(Dir, "clocks.json"); } }

        public static List<Clock> Load()
        {
            try { if (File.Exists(File_)) return Net.FromJson<List<Clock>>(File.ReadAllText(File_)) ?? new List<Clock>(); } catch { }
            return new List<Clock>();
        }

        public static void Save(List<Clock> clocks)
        {
            try { Directory.CreateDirectory(Dir); File.WriteAllText(File_, Net.ToJson(clocks)); } catch { }
        }
    }

    // ------------------------------------------------------------------ a clock's settings, read from its settings page

    class ClockSettings
    {
        public Dictionary<string, string> V = new Dictionary<string, string>();
        public HashSet<string> On = new HashSet<string>();
        public bool Full, PassSaved, SecretSaved;

        public string Get(string k, string def = "") { string v; return V.TryGetValue(k, out v) ? v : def; }

        static Dictionary<string, string> Attrs(string t)
        {
            var d = new Dictionary<string, string>();
            foreach (Match m in Regex.Matches(t, "([\\w-]+)(?:=(?:\"([^\"]*)\"|([^\\s\"'>]+)))?"))
                d[m.Groups[1].Value.ToLowerInvariant()] = WebUtility.HtmlDecode(m.Groups[2].Success ? m.Groups[2].Value : m.Groups[3].Value);
            return d;
        }

        public static ClockSettings Parse(string html)
        {
            if (html == null || html.IndexOf("action=/save", StringComparison.OrdinalIgnoreCase) < 0) return null;
            var s = new ClockSettings();
            foreach (Match m in Regex.Matches(html, "<input\\b([^>]*)>", RegexOptions.IgnoreCase))
            {
                var a = Attrs(m.Groups[1].Value);
                string name;
                if (!a.TryGetValue("name", out name)) continue;
                string type = a.ContainsKey("type") ? a["type"] : "text";
                string val = a.ContainsKey("value") ? a["value"] : "";
                bool chk = a.ContainsKey("checked");
                if (type == "checkbox") { if (chk) s.On.Add(name); continue; }
                if (type == "radio") { if (chk) s.V[name] = val; continue; }
                if (type == "hidden") continue;
                string ph = a.ContainsKey("placeholder") ? a["placeholder"] : "";
                if (name == "pass") { s.PassSaved = ph.StartsWith("(saved"); continue; }
                if (name == "secret") { s.SecretSaved = ph.StartsWith("(saved"); continue; }
                s.V[name] = val;
            }
            foreach (Match m in Regex.Matches(html, "<select\\b([^>]*)>(.*?)</select>", RegexOptions.IgnoreCase | RegexOptions.Singleline))
            {
                var a = Attrs(m.Groups[1].Value);
                string name;
                if (!a.TryGetValue("name", out name)) continue;
                string first = null, sel = null;
                foreach (Match o in Regex.Matches(m.Groups[2].Value, "<option value=(?:\"([^\"]*)\"|([^\\s>]*))([^>]*)>"))
                {
                    var v = WebUtility.HtmlDecode(o.Groups[1].Success ? o.Groups[1].Value : o.Groups[2].Value);
                    if (first == null) first = v;
                    if (o.Groups[3].Value.Contains("selected")) sel = v;
                }
                s.V[name] = sel ?? first ?? "";
            }
            s.Full = s.V.ContainsKey("theme");
            return s;
        }
    }

    // ------------------------------------------------------------------ the graph

    class GraphView : Control
    {
        public double?[] S, T;
        public bool Fahrenheit;
        public int RangeH = -1;

        public GraphView()
        {
            DoubleBuffered = true; ResizeRedraw = true; BackColor = UI.Card;
            Font = UI.F(9);
        }

        static double?[] Arr(object o)
        {
            var a = o as object[];
            if (a == null) { var al = o as System.Collections.ArrayList; if (al != null) a = al.ToArray(); }
            if (a == null) return null;
            return a.Select(x => UI.Num(x)).ToArray();
        }

        public void SetData(Dictionary<string, object> g)
        {
            S = g == null ? null : Arr(g.ContainsKey("s") ? g["s"] : null);
            T = g == null ? null : Arr(g.ContainsKey("t") ? g["t"] : null);
            if (T != null && Fahrenheit) T = T.Select(t => t.HasValue ? t * 9 / 5 + 32 : null).ToArray();
            Invalidate();
        }

        static double[] Range(double?[] a, double minPad)
        {
            if (a == null) return null;
            var f = a.Where(x => x.HasValue).Select(x => x.Value).ToList();
            if (f.Count == 0) return null;
            double lo = f.Min(), hi = f.Max(), p = (hi - lo) * 0.08;
            if (p < minPad) p = minPad;
            return new[] { lo - p, hi + p };
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
            g.Clear(UI.Behind(this));
            using (var card = UI.Round(new RectangleF(0, 0, Width - 1, Height - 1), UI.Pf(12)))
            using (var b = new SolidBrush(BackColor)) g.FillPath(b, card);
            var dim = new SolidBrush(UI.Dim);
            var rs = Range(S, 0.002);
            var rt = Range(T, 0.3);
            if (rs == null && rt == null)
            {
                var msg = "No readings to draw yet";
                var sz = g.MeasureString(msg, UI.F(11));
                g.DrawString(msg, UI.F(11), dim, (Width - sz.Width) / 2, (Height - sz.Height) / 2);
                return;
            }
            float x0 = UI.Pf(66), x1 = Width - UI.Pf(62), y0 = UI.Pf(46), y1 = Height - UI.Pf(34);
            if (x1 - x0 < 50 || y1 - y0 < 40) return;

            // legend
            float lx = x0, sq = UI.Pf(10), ly = UI.Pf(18);
            if (rs != null) { using (var p = UI.Round(new RectangleF(lx, ly, sq, sq), UI.Pf(2))) g.FillPath(new SolidBrush(UI.Amber), p); g.DrawString("Gravity", Font, new SolidBrush(UI.Text), lx + UI.Pf(15), ly - UI.Pf(4)); lx += UI.Pf(84); }
            if (rt != null) { using (var p = UI.Round(new RectangleF(lx, ly, sq, sq), UI.Pf(2))) g.FillPath(new SolidBrush(UI.Blue), p); g.DrawString("Temperature", Font, new SolidBrush(UI.Text), lx + UI.Pf(15), ly - UI.Pf(4)); }

            var grid = new Pen(UI.Line);
            var right = new StringFormat { Alignment = StringAlignment.Far, LineAlignment = StringAlignment.Center };
            var left = new StringFormat { Alignment = StringAlignment.Near, LineAlignment = StringAlignment.Center };
            for (int k = 0; k <= 4; k++)
            {
                float y = y0 + (y1 - y0) * k / 4f;
                g.DrawLine(grid, x0, y, x1, y);
                if (rs != null) g.DrawString((rs[1] - (rs[1] - rs[0]) * k / 4).ToString("0.000"), Font, new SolidBrush(UI.Amber), x0 - UI.Pf(8), y, right);
                if (rt != null) g.DrawString((rt[1] - (rt[1] - rt[0]) * k / 4).ToString("0.0") + UI.Deg, Font, new SolidBrush(UI.Blue), x1 + UI.Pf(8), y, left);
            }
            string from = RangeH == 0 ? "start" : RangeH == 24 ? "-24 h" : RangeH > 0 ? "-" + (RangeH / 24) + " days" : "earlier";
            g.DrawString(from, Font, dim, x0, y1 + UI.Pf(8));
            var now = new StringFormat { Alignment = StringAlignment.Far };
            g.DrawString("now", Font, dim, x1, y1 + UI.Pf(8), now);

            if (rt != null) Plot(g, T, rt, new Pen(UI.Blue, UI.Pf(1.6f)) { LineJoin = LineJoin.Round }, x0, x1, y0, y1);
            if (rs != null) Plot(g, S, rs, new Pen(UI.Amber, UI.Pf(2.6f)) { LineJoin = LineJoin.Round, StartCap = LineCap.Round, EndCap = LineCap.Round }, x0, x1, y0, y1);
        }

        static void Plot(Graphics g, double?[] a, double[] r, Pen pen, float x0, float x1, float y0, float y1)
        {
            int n = a.Length;
            var seg = new List<PointF>();
            Action flush = () =>
            {
                if (seg.Count >= 2) g.DrawLines(pen, seg.ToArray());
                else if (seg.Count == 1) g.FillEllipse(new SolidBrush(pen.Color), seg[0].X - 2, seg[0].Y - 2, 4, 4);
                seg.Clear();
            };
            for (int i = 0; i < n; i++)
            {
                if (!a[i].HasValue) { flush(); continue; }
                float x = n == 1 ? x1 : x0 + (x1 - x0) * i / (float)(n - 1);
                float y = (float)(y1 - (a[i].Value - r[0]) / (r[1] - r[0]) * (y1 - y0));
                seg.Add(new PointF(x, y));
            }
            flush();
        }
    }

    // A rounded card for grouping things; see-through labels on it show the card colour
    class Card : Panel
    {
        public Card()
        {
            BackColor = UI.Card;
            SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint | ControlStyles.ResizeRedraw, true);
        }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(UI.Behind(this));
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var p = UI.Round(new RectangleF(0, 0, Width - 1, Height - 1), UI.Pf(12)))
            using (var b = new SolidBrush(BackColor)) g.FillPath(b, p);
        }
    }

    // A box with a caption, a big value and a small line under it
    class Tile : Panel
    {
        public Label Cap, Val, Sub;
        public Tile(string caption, Color accent)
        {
            Size = new Size(176, 96); BackColor = UI.Card; Margin = new Padding(0, 0, 12, 12);
            SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint | ControlStyles.ResizeRedraw, true);
            Cap = UI.Lbl(caption, 9.5f, false, UI.Dim); Cap.Location = new Point(16, 12);
            Val = UI.Lbl("-", 20, true, accent); Val.Location = new Point(13, 31);
            Sub = UI.Lbl("", 9, false, UI.Dim); Sub.Location = new Point(16, 70);
            Controls.AddRange(new Control[] { Cap, Val, Sub });
        }

        // A rounded card; the labels on it are see-through, so they show this
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(UI.Behind(this));
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var p = UI.Round(new RectangleF(0, 0, Width - 1, Height - 1), UI.Pf(12)))
            using (var b = new SolidBrush(BackColor)) g.FillPath(b, p);
        }
    }

    // ------------------------------------------------------------------ main window

    class MainForm : DarkForm
    {
        List<Clock> clocks = Renamed(Store.Load());

        // Names this app gave before clocks were called FermFrames ("Clock 2" becomes "FermFrame 2")
        static List<Clock> Renamed(List<Clock> list)
        {
            bool changed = false;
            foreach (var c in list)
            {
                var m = Regex.Match(c.Name ?? "", "^Clock (\\d+)$");
                if (m.Success) { c.Name = "FermFrame " + m.Groups[1].Value; changed = true; }
            }
            if (changed) Store.Save(list);
            return list;
        }
        ListBox list;
        Label status, title, subtitle, empty;
        Panel right, content, pageOverview, pageSettings, pageFirmware;
        Button[] tabs;
        int tab;
        System.Windows.Forms.Timer timer;
        bool polling;
        DateTime lastScan = DateTime.MinValue;

        // overview
        Label brew, brewSub, problem, details;
        Tile tSg, tTemp, tAbv, tDay;
        GraphView graph;

        public MainForm()
        {
            Text = "FermFrame";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            ClientSize = new Size(1120, 760);
            MinimumSize = new Size(900, 620);
            StartPosition = FormStartPosition.CenterScreen;

            BuildLeft();
            BuildRight();
            FinishLayout();
            list.ItemHeight = UI.Px(70);

            timer = new System.Windows.Forms.Timer { Interval = 60000 };
            timer.Tick += (s, e) => Poll(false);
            timer.Start();

            Shown += (s, e) =>
            {
                RefreshList();
                if (clocks.Count > 0) list.SelectedIndex = 0;
                ShowClock();
                Poll(clocks.Count == 0);
            };
        }

        Clock Sel { get { return list.SelectedIndex >= 0 && list.SelectedIndex < clocks.Count ? clocks[list.SelectedIndex] : null; } }

        // ---------------- left side: the clocks

        void BuildLeft()
        {
            var side = new Panel { Dock = DockStyle.Left, Width = 290, BackColor = UI.Bg, Padding = new Padding(16, 18, 16, 14) };

            list = new ListBox
            {
                Dock = DockStyle.Fill, BorderStyle = BorderStyle.None, BackColor = UI.Bg, ForeColor = UI.Text,
                DrawMode = DrawMode.OwnerDrawFixed, ItemHeight = 70, IntegralHeight = false
            };
            list.DrawItem += DrawClockItem;
            Native.DarkScroll(list);
            list.SelectedIndexChanged += (s, e) => { if (!listing) ShowClock(true); };

            var head = new Panel { Dock = DockStyle.Top, Height = 74 };
            var logo = UI.Lbl("FermFrame", 20, true, UI.Amber); logo.Location = new Point(0, 0);
            var sub = UI.Lbl("My FermFrames", 10, false, UI.Dim); sub.Location = new Point(3, 42);
            head.Controls.AddRange(new Control[] { logo, sub });

            var bottom = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 196, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(0, 8, 0, 0) };
            var find = UI.Btn("Find my FermFrames"); find.AutoSize = false; find.Width = 256;
            find.Click += (s, e) => Poll(true);
            var buy = UI.Btn("Buy another FermFrame"); buy.AutoSize = false; buy.Width = 256;
            buy.Click += (s, e) => OpenBuy();
            var add = UI.Btn("+  Set up a new FermFrame"); add.AutoSize = false; add.Width = 256;
            add.Click += (s, e) => OpenSetup();
            status = UI.Lbl("", 9, false, UI.Dim); status.AutoSize = false; status.Size = new Size(256, 36);
            var about = new LinkLabel { Text = "About FermFrame " + AppInfo.Version, AutoSize = true, Font = UI.F(9), LinkColor = UI.Dim, ActiveLinkColor = UI.Amber, VisitedLinkColor = UI.Dim, LinkBehavior = LinkBehavior.HoverUnderline, Margin = new Padding(3, 0, 0, 0) };
            about.LinkClicked += (s, e) => { using (var f = new AboutForm()) f.ShowDialog(this); };
            bottom.Controls.AddRange(new Control[] { find, buy, add, status, about });

            side.Controls.Add(list);
            side.Controls.Add(head);
            side.Controls.Add(bottom);
            list.BringToFront();
            Controls.Add(side);
        }

        static readonly Font nameFont = UI.F(11, true), lineFont = UI.F(9);

        void DrawClockItem(object sender, DrawItemEventArgs e)
        {
            if (e.Index < 0 || e.Index >= clocks.Count) return;
            var c = clocks[e.Index];
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            bool sel = (e.State & DrawItemState.Selected) != 0;
            using (var b = new SolidBrush(UI.Bg)) g.FillRectangle(b, e.Bounds);
            var r = new Rectangle(e.Bounds.X, e.Bounds.Y + UI.Px(3), e.Bounds.Width - 1, e.Bounds.Height - UI.Px(6));
            if (sel)
            {
                using (var p = UI.Round(r, UI.Pf(10))) using (var b = new SolidBrush(UI.Card)) g.FillPath(b, p);
                using (var p = UI.Round(new RectangleF(r.X + UI.Pf(6), r.Y + UI.Pf(14), UI.Pf(4), r.Height - UI.Pf(28)), UI.Pf(2))) using (var b = new SolidBrush(UI.Amber)) g.FillPath(b, p);
            }

            Color dot = !c.Online ? Color.FromArgb(110, 110, 110) : c.Problem != "" ? UI.Bad : UI.Good;
            float d = UI.Pf(10);
            using (var b = new SolidBrush(dot)) g.FillEllipse(b, r.X + UI.Pf(18), r.Y + UI.Pf(16), d, d);
            TextRenderer.DrawText(g, c.Name, nameFont, new Point(r.X + UI.Px(36), r.Y + UI.Px(8)), UI.Text);
            string line = c.Busy ? "Updating..." : !c.Online ? "Not answering" + (c.Ip != "" ? UI.Dot + c.Ip : "")
                        : c.Problem != "" ? c.Problem : "On Wi-Fi" + UI.Dot + c.Ip;
            TextRenderer.DrawText(g, line, lineFont, new Rectangle(r.X + UI.Px(36), r.Y + UI.Px(34), r.Width - UI.Px(42), UI.Px(20)),
                c.Online && c.Problem != "" ? UI.Bad : UI.Dim, TextFormatFlags.EndEllipsis);
        }

        bool listing;

        void RefreshList()
        {
            int i = list.SelectedIndex;
            listing = true;
            list.BeginUpdate();
            list.Items.Clear();
            foreach (var c in clocks) list.Items.Add(c.Name);
            if (i >= clocks.Count) i = clocks.Count - 1;
            if (i < 0 && clocks.Count > 0) i = 0;
            list.SelectedIndex = i;
            list.EndUpdate();
            list.Invalidate();
            listing = false;
        }

        void SetStatus(string s) { status.Text = s; }

        // ---------------- right side: one clock

        void BuildRight()
        {
            right = new Panel { Dock = DockStyle.Fill, Padding = new Padding(28, 20, 28, 16) };
            right.Paint += (s, e) => { using (var pen = new Pen(UI.Line)) e.Graphics.DrawLine(pen, 0, 0, 0, right.Height); };   // line between the list and the rest

            var head = new Panel { Dock = DockStyle.Top, Height = 66 };
            title = UI.Lbl("", 20, true); title.Location = new Point(0, 0);
            subtitle = UI.Lbl("", 10, false, UI.Dim); subtitle.Location = new Point(2, 40);
            head.Controls.AddRange(new Control[] { title, subtitle });

            var strip = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 50, WrapContents = false };
            var names = new[] { "Overview", "Settings", "Firmware" };
            tabs = new Button[names.Length];
            for (int i = 0; i < names.Length; i++)
            {
                int k = i;
                var b = UI.Btn(names[i]); b.AutoSize = false; b.Width = 120; b.Margin = new Padding(0, 4, 8, 6);
                b.Click += (s, e) => SetTab(k);
                tabs[i] = b; strip.Controls.Add(b);
            }

            content = new Panel { Dock = DockStyle.Fill, Padding = new Padding(0, 10, 0, 0) };
            pageOverview = BuildOverview();
            pageSettings = BuildSettings();
            pageFirmware = BuildFirmware();
            content.Controls.AddRange(new Control[] { pageOverview, pageSettings, pageFirmware });

            empty = UI.Lbl("No FermFrames yet.\n\nPress \"Find my FermFrames\" to look for FermFrames on your Wi-Fi,\nor \"Set up a new FermFrame\" to turn a brand-new clock into one.", 12, false, UI.Dim);
            empty.Location = new Point(40, 60);

            right.Controls.Add(content);
            right.Controls.Add(strip);
            right.Controls.Add(head);
            right.Controls.Add(empty);
            content.BringToFront();
            Controls.Add(right);
            right.BringToFront();
            SetTab(0);
        }

        void SetTab(int k)
        {
            tab = k;
            for (int i = 0; i < tabs.Length; i++)
            {
                tabs[i].BackColor = i == k ? UI.Amber : UI.Field;
                tabs[i].ForeColor = i == k ? Color.Black : UI.Text;
                tabs[i].FlatAppearance.MouseOverBackColor = i == k ? UI.Amber : Color.FromArgb(60, 60, 60);
            }
            pageOverview.Visible = k == 0; pageSettings.Visible = k == 1; pageFirmware.Visible = k == 2;
            var c = Sel;
            if (k == 1 && c != null && c.Online && c.Settings == null) LoadSettings(c);
            if (k == 2) ShowFirmware();
        }

        void ShowClock(bool fetch = false)
        {
            var c = Sel;
            bool any = c != null;
            empty.Visible = !any;
            foreach (Control x in right.Controls) if (x != empty) x.Visible = any;
            if (!any) return;
            title.Text = c.Name;
            subtitle.Text = !c.Online ? "Not answering" + (c.Ip != "" ? " at " + c.Ip : "") + ". Check it's plugged in, then press Find my FermFrames."
                : "FermFrame " + c.Version + UI.Dot + c.Ip + UI.Dot + "ID " + c.Mac;
            subtitle.ForeColor = c.Online ? UI.Dim : UI.Bad;
            ShowOverview();
            if (tab == 1) { FillSettings(); if (c.Online && c.Settings == null && fetch) LoadSettings(c); }
            if (tab == 2) ShowFirmware();
            if (fetch && c.Online) LoadGraph(c);
        }

        // ---------------- checking on the clocks

        void Poll(bool scan)
        {
            if (polling) return;
            polling = true;
            SetStatus(scan ? "Looking for FermFrames on your Wi-Fi..." : "Checking your FermFrames...");
            var known = clocks.Where(c => !c.Busy && c.Ip != "").Select(c => c.Ip).Distinct().ToList();
            var macs = clocks.Where(c => !c.Busy).Select(c => c.Mac).ToList();
            var sel = Sel;
            string selMac = sel == null ? null : sel.Mac;
            bool wantScan = scan || (DateTime.Now - lastScan).TotalMinutes > 3;

            ThreadPool.QueueUserWorkItem(_ =>
            {
                var infos = new Dictionary<string, Found>();
                var lk = new object();
                Parallel.ForEach(known, ip =>
                {
                    var j = Net.ClockInfo("http://" + ip, 5);
                    if (j != null) lock (lk) infos[UI.Str(j, "mac")] = new Found { Kind = "fermframe", Ip = ip, Info = j };
                });
                bool missing = macs.Any(m => !infos.ContainsKey(m));
                bool scanned = false;
                if (scan || (missing && wantScan))
                {
                    scanned = true;
                    foreach (var f in Net.Scan(false)) infos[UI.Str(f.Info, "mac")] = f;
                }
                Dictionary<string, object> g = null;
                Found fs;
                if (selMac != null && infos.TryGetValue(selMac, out fs))
                {
                    var r = Net.Get(fs.Base + "/graph.json", 10);
                    if (r.Status == 200) g = Net.Json(r.Body);
                }
                BeginInvoke(new Action(() =>
                {
                    polling = false;
                    if (scanned) lastScan = DateTime.Now;
                    int added = 0;
                    foreach (var f in infos.Values)
                    {
                        var mac = UI.Str(f.Info, "mac");
                        if (mac == "") continue;
                        var c = clocks.FirstOrDefault(x => x.Mac == mac);
                        if (c == null) { c = new Clock { Mac = mac, Name = NextName() }; clocks.Add(c); added++; }
                        if (c.Busy) continue;
                        if (c.Ip != f.Ip) { c.Ip = f.Ip; c.Settings = null; }
                        c.Info = f.Info; c.Online = true; c.Checked = DateTime.Now;
                    }
                    foreach (var c in clocks) if (!c.Busy && !infos.ContainsKey(c.Mac)) c.Online = false;
                    if (g != null && Sel != null && Sel.Mac == selMac) Sel.Graph = g;
                    Store.Save(clocks);
                    RefreshList();
                    ShowClock();
                    if (Sel != null && Sel.Online && Sel.Graph == null) LoadGraph(Sel);
                    if (tab == 1 && Sel != null && Sel.Online && Sel.Settings == null) LoadSettings(Sel);
                    int on = clocks.Count(c => c.Online);
                    SetStatus((clocks.Count == 0 ? "No FermFrames found on this Wi-Fi."
                        : on + " of " + clocks.Count + " FermFrame" + (clocks.Count == 1 ? "" : "s") + " answering" + (added > 0 ? " (" + added + " new)" : "") + ".")
                        + "\nChecked " + DateTime.Now.ToString("HH:mm"));
                }));
            });
        }

        string NextName()
        {
            for (int n = 1; ; n++) if (!clocks.Any(c => c.Name == "FermFrame " + n)) return "FermFrame " + n;
        }

        void LoadGraph(Clock c)
        {
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var r = Net.Get(c.Base + "/graph.json", 10);
                var g = r.Status == 200 ? Net.Json(r.Body) : null;
                if (g != null) BeginInvoke(new Action(() => { c.Graph = g; if (Sel == c) ShowOverview(); }));
            });
        }

        // ---------------- Overview

        Panel BuildOverview()
        {
            var p = new Panel { Dock = DockStyle.Fill };

            var top = new Panel { Dock = DockStyle.Top, Height = 70 };
            brew = UI.Lbl("", 15, true, UI.Text); brew.Location = new Point(0, 0);
            brewSub = UI.Lbl("", 10, false, UI.Dim); brewSub.Location = new Point(2, 34);
            top.Controls.AddRange(new Control[] { brew, brewSub });

            var tiles = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 110, WrapContents = false };
            tSg = new Tile("Gravity", UI.Amber); tTemp = new Tile("Temperature", UI.Blue);
            tAbv = new Tile("ABV so far", UI.Text); tDay = new Tile("Fermenting", UI.Text);
            tiles.Controls.AddRange(new Control[] { tSg, tTemp, tAbv, tDay });

            problem = UI.Lbl("", 10.5f, true, UI.Bad); problem.AutoSize = false; problem.Dock = DockStyle.Top; problem.Height = 0;

            var gwrap = new Panel { Dock = DockStyle.Fill, Padding = new Padding(0, 4, 0, 10) };
            graph = new GraphView { Dock = DockStyle.Fill };
            gwrap.Controls.Add(graph);

            var bottom = new Panel { Dock = DockStyle.Bottom, Height = 84 };
            details = UI.Lbl("", 9, false, UI.Dim); details.Location = new Point(0, 0);
            var btns = new FlowLayoutPanel { Location = new Point(0, 36), Size = new Size(900, 46), WrapContents = false };
            var refresh = UI.Btn("Refresh"); refresh.Click += (s, e) => Poll(false);
            var web = UI.Btn("Open the FermFrame's web page"); web.Click += (s, e) => { if (Sel != null && Sel.Ip != "") Process.Start(Sel.Base + "/"); };
            var rename = UI.Btn("Rename"); rename.Click += (s, e) => RenameClock();
            var forget = UI.Btn("Remove from list"); forget.Click += (s, e) => ForgetClock();
            btns.Controls.AddRange(new Control[] { refresh, web, rename, forget });
            bottom.Controls.AddRange(new Control[] { details, btns });

            p.Controls.Add(gwrap);
            p.Controls.Add(problem);
            p.Controls.Add(tiles);
            p.Controls.Add(top);
            p.Controls.Add(bottom);
            gwrap.BringToFront();
            return p;
        }

        bool IsF(Clock c) { return c.Settings != null && c.Settings.Get("unit", "C") == "F"; }

        void ShowOverview()
        {
            var c = Sel;
            if (c == null) return;
            var g = c.Graph;
            var name = UI.Str(g, "name");
            brew.Text = name != "" ? name : c.Online ? "No brew showing" : "";
            double? sg = g == null ? null : UI.Num(Get(g, "sg"));
            double? temp = g == null ? null : UI.Num(Get(g, "temp"));
            double? og = g == null ? null : UI.Num(Get(g, "og"));
            double? bat = g == null ? null : UI.Num(Get(g, "bat"));
            double? day = g == null ? null : UI.Num(Get(g, "day"));
            bool ready = g != null && UI.Num(Get(g, "ready")) == 1;
            bool f = IsF(c);

            tSg.Val.Text = sg.HasValue ? sg.Value.ToString("0.000") : "-";
            tSg.Sub.Text = og.HasValue ? "Started at " + og.Value.ToString("0.000") : "";
            tTemp.Val.Text = temp.HasValue ? (f ? temp.Value * 9 / 5 + 32 : temp.Value).ToString("0.0") + UI.Deg + (f ? "F" : "C") : "-";
            tTemp.Sub.Text = bat.HasValue ? "Battery " + bat.Value + "%" : "";
            tAbv.Val.Text = sg.HasValue && og.HasValue ? ((og.Value - sg.Value) * 131.25).ToString("0.0") + "%" : "-";
            tAbv.Sub.Text = sg.HasValue && og.HasValue && og.Value > 1 ? "Attenuation " + ((og.Value - sg.Value) / (og.Value - 1) * 100).ToString("0") + "%" : "";
            tDay.Val.Text = day.HasValue ? "Day " + day.Value : "-";
            tDay.Val.ForeColor = ready ? UI.Good : UI.Text;
            tDay.Sub.Text = ready ? "Ready to keg?" : "";
            tDay.Sub.ForeColor = ready ? UI.Good : UI.Dim;

            var brews = UI.Num(Get(c.Info, "brews"));
            brewSub.Text = !c.Online ? "Last seen before this app was opened" :
                (brews.HasValue && brews.Value > 1 ? (brews.Value - 1) + " other brew" + (brews.Value > 2 ? "s" : "") + " fermenting too" : "")
                + (ready ? (brews.HasValue && brews.Value > 1 ? UI.Dot : "") + "Gravity hasn't moved for 3 days" : "");

            var pr = c.Online ? c.Problem : "";
            problem.Text = pr == "" ? "" : "The FermFrame says: " + pr + Explain(pr);
            problem.Height = pr == "" ? 0 : UI.Px(34);

            graph.Fahrenheit = f;
            graph.RangeH = c.Settings == null ? -1 : (int)(UI.Num(c.Settings.Get("range", "-1")) ?? -1);
            graph.SetData(g);

            var heap = UI.Num(Get(c.Info, "heap"));
            var readings = UI.Num(Get(c.Info, "readings"));
            details.Text = c.Info == null ? "" : "Wi-Fi " + UI.Str(c.Info, "wifi") + UI.Dot + (readings ?? 0) + " readings" + UI.Dot + "free memory "
                + (heap.HasValue ? (heap.Value / 1024).ToString("0") + " KB" : "?") + UI.Dot + "checked " + c.Checked.ToString("HH:mm");
        }

        static object Get(Dictionary<string, object> d, string k) { object o; return d != null && d.TryGetValue(k, out o) ? o : null; }

        static string Explain(string p)
        {
            if (Regex.IsMatch(p, "wrong ID|wrong email", RegexOptions.IgnoreCase)) return "  - check the ID and key on the Settings tab.";
            if (Regex.IsMatch(p, "Fermenting", RegexOptions.IgnoreCase)) return "  - in Brewfather, set your batch's status to Fermenting.";
            return "";
        }

        void RenameClock()
        {
            var c = Sel; if (c == null) return;
            var n = Prompt.Ask(this, "Rename FermFrame", "Name for this FermFrame (e.g. Garage fridge):", c.Name);
            if (string.IsNullOrWhiteSpace(n)) return;
            c.Name = n.Trim(); Store.Save(clocks); RefreshList(); ShowClock();
        }

        void ForgetClock()
        {
            var c = Sel; if (c == null) return;
            if (MessageBox.Show(this, "Remove " + c.Name + " from this list?\n\nNothing changes on the FermFrame. If it's still on your Wi-Fi, Find my FermFrames will add it back.",
                "FermFrame", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            clocks.Remove(c); Store.Save(clocks); RefreshList(); ShowClock();
        }

        // ---------------- Settings

        Panel setScroll;
        Label setNote, saveStatus, lUser, lKey, lBright, passHint, keyHint;
        TextBox tSsid, tPass, tUser, tKey, tDevice;
        RadioButton rBf, rRapt, rC, rF;
        ComboBox cTheme, cFont, cNLevel, cGraph, cPlot, cRange, cTz;
        Button[] colours;
        Slider trBright;
        CheckBox kNight, kGraph, kBig, kClock, kBrews, kKeg;
        ComboBox dFrom, dTo, nSecs, nRefresh;
        Button saveBtn;
        List<Control> lookControls = new List<Control>();
        bool filling;
        static readonly string[] ColourKeys = { "cBg", "cText", "cSg", "cTemp", "cGrid", "cDim" };
        static readonly string[] ColourNames = { "Background", "Text", "Gravity", "Temperature", "Grid", "Small text" };
        static readonly Dictionary<string, string[]> Themes = new Dictionary<string, string[]>
        {
            { "beer", new[] { "#000000", "#ffffff", "#ffcc00", "#5bbeff", "#2a2a2a", "#8c8c8c" } },
            { "dark", new[] { "#101418", "#e6e6e6", "#7cd992", "#f2a65a", "#2b3138", "#8a949e" } },
            { "light", new[] { "#ffffff", "#111111", "#c77700", "#1f6fd1", "#dddddd", "#777777" } },
            { "retro", new[] { "#000000", "#33ff66", "#33ff66", "#b8ff66", "#0f3318", "#1f9944" } },
            { "contrast", new[] { "#000000", "#ffffff", "#ffff00", "#00ffff", "#555555", "#cccccc" } },
        };

        Panel BuildSettings()
        {
            var p = new Panel { Dock = DockStyle.Fill };
            setScroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
            Native.DarkScroll(setScroll);
            int y = 0, W = 560;
            // Each section sits on its own card; X/y are positions inside the current card
            Panel cur = setScroll; int X = 0;
            Action<Control> add = c => cur.Controls.Add(c);
            Action close = () => { if (cur != setScroll) { cur.Height = y + 10; y = cur.Bottom + 16; } };
            Func<string, Label> head = t =>
            {
                close();
                cur = new Card { Location = new Point(0, y), Width = W + 40 }; setScroll.Controls.Add(cur);
                X = 20; y = 6;
                var l = UI.Lbl(t, 13, true, UI.Amber); l.Location = new Point(X, y + 10); add(l); y += 50; return l;
            };
            Func<string, Label> label = t => { var l = UI.Lbl(t, 10, false, UI.Dim); l.Location = new Point(X, y); add(l); y += 24; return l; };
            Func<Control, int, Control> put = (c, h) => { c.Location = new Point(X, y); if (c.Width < 50 || !(c is CheckBox)) c.Width = W; add(c); y += h; return c; };
            Func<string, CheckBox> check = t => { var k = UI.Check(t); k.Location = new Point(X, y); add(k); y += 32; return k; };

            setNote = UI.Lbl("", 10, false, UI.Dim); setNote.AutoSize = false; setNote.Size = new Size(W, 44); setNote.Location = new Point(0, 0);
            setScroll.Controls.Add(setNote); y = 40;

            head("1. Home Wi-Fi");
            label("Wi-Fi name"); tSsid = UI.Box(); put(tSsid, 38);
            label("Wi-Fi password"); tPass = UI.Box(true); put(tPass, 32);
            passHint = UI.Lbl("Leave blank to keep the saved password.", 9, false, UI.Dim); passHint.Location = new Point(X, y); add(passHint); y += 24;

            head("2. Where are the readings?");
            rBf = UI.Radio("Brewfather");
            rRapt = UI.Radio("RAPT Pill");
            var srcBox = new Panel { Location = new Point(X, y), Size = new Size(W, 30) };
            rBf.Location = new Point(0, 2); rRapt.Location = new Point(160, 2);
            srcBox.Controls.AddRange(new Control[] { rBf, rRapt }); add(srcBox); y += 36;
            rBf.CheckedChanged += (s, e) => SourceLabels();
            lUser = label("Brewfather user ID"); tUser = UI.Box(); put(tUser, 38);
            lKey = label("Brewfather API key"); tKey = UI.Box(true); put(tKey, 32);
            keyHint = UI.Lbl("Leave blank to keep the saved key.", 9, false, UI.Dim); keyHint.Location = new Point(X, y); add(keyHint); y += 24;

            head("3. Look & feel"); lookControls.Add(cur);
            label("Theme"); cTheme = UI.Combo(); put(cTheme, 40);
            Opt.Fill(cTheme, "beer", "Beer (amber on black)", "dark", "Dark", "light", "Light", "retro", "Retro green", "contrast", "High contrast", "custom", "My own colours");
            cTheme.SelectedIndexChanged += (s, e) => { if (filling) return; string[] t; if (Themes.TryGetValue(Opt.Of(cTheme), out t)) for (int i = 0; i < 6; i++) colours[i].BackColor = ColorTranslator.FromHtml(t[i]); };
            colours = new Button[6];
            for (int i = 0; i < 6; i++)
            {
                int k = i;
                var l = UI.Lbl(ColourNames[i], 9, false, UI.Dim); l.Location = new Point(X + (i % 3) * 190, y + (i / 3) * 56); add(l);
                var b = new RButton { Swatch = true, Size = new Size(176, 28), Location = new Point(X + (i % 3) * 190, y + 20 + (i / 3) * 56) };
                b.Click += (s, e) =>
                {
                    using (var d = new ColorDialog { Color = colours[k].BackColor, FullOpen = true })
                        if (d.ShowDialog(this) == DialogResult.OK) { colours[k].BackColor = d.Color; filling = true; Opt.Select(cTheme, "custom"); filling = false; }
                };
                colours[i] = b; add(b);
            }
            y += 118;
            label("Font"); cFont = UI.Combo(); put(cFont, 40);
            Opt.Fill(cFont, "digital", "Digital (7-segment)", "modern", "Modern", "classic", "Classic", "mono", "Typewriter");
            lBright = label("Brightness");
            trBright = new Slider { Minimum = 5, Maximum = 100, Step = 5 };
            put(trBright, 44);
            trBright.ValueChanged += (s, e) => lBright.Text = "Brightness  " + trBright.Value + "%";
            kNight = check("Dim at night");
            var nightRow = new Panel { Location = new Point(X, y), Size = new Size(W, 56) };
            var lf = UI.Lbl("From", 9, false, UI.Dim); lf.Location = new Point(0, 0);
            dFrom = TimeCombo(); dFrom.Location = new Point(0, 22);
            var lt = UI.Lbl("To", 9, false, UI.Dim); lt.Location = new Point(190, 0);
            dTo = TimeCombo(); dTo.Location = new Point(190, 22);
            var ll = UI.Lbl("Night level", 9, false, UI.Dim); ll.Location = new Point(380, 0);
            cNLevel = UI.Combo(); cNLevel.Location = new Point(380, 22); cNLevel.Width = 176;
            Opt.Fill(cNLevel, "0", "Off", "5", "Very dim", "15", "Dim", "30", "Medium");
            nightRow.Controls.AddRange(new Control[] { lf, dFrom, lt, dTo, ll, cNLevel });
            add(nightRow); y += 66;
            var tzNote = UI.Lbl("Night dimming uses the time zone under 6. More.", 9, false, UI.Dim); tzNote.Location = new Point(X, y); add(tzNote); y += 24;

            head("4. Graph"); lookControls.Add(cur);
            label("Graph style"); cGraph = UI.Combo(); put(cGraph, 40);
            Opt.Fill(cGraph, "line", "Line", "area", "Filled area", "dots", "Dots", "bars", "Daily bars");
            label("Show"); cPlot = UI.Combo(); put(cPlot, 40);
            Opt.Fill(cPlot, "both", "Gravity and temperature", "sg", "Gravity only", "temp", "Temperature only");
            label("Time range"); cRange = UI.Combo(); put(cRange, 40);
            Opt.Fill(cRange, "24", "Last 24 hours", "72", "Last 3 days", "168", "Last 7 days", "336", "Last 14 days", "0", "Whole batch");

            head("5. Pages"); lookControls.Add(cur);
            var pn = UI.Lbl("Tick more than one and the FermFrame takes turns showing them.", 9, false, UI.Dim); pn.Location = new Point(X, y); add(pn); y += 26;
            kGraph = check("Graph"); kBig = check("Big number"); kClock = check("Clock (time and date)"); kBrews = check("My other fermenting brews");
            label("Seconds per page");
            nSecs = UI.Combo(); nSecs.Width = 176;
            Opt.Fill(nSecs, "5", "5 seconds", "10", "10 seconds", "15", "15 seconds", "20", "20 seconds", "30", "30 seconds", "45", "45 seconds",
                "60", "1 minute", "120", "2 minutes", "300", "5 minutes", "600", "10 minutes");
            nSecs.Location = new Point(X, y); add(nSecs); y += 42;
            kKeg = check("Show \"Ready to keg?\" when gravity hasn't moved for 3 days");
            var reset = UI.Btn("Reset the look to normal"); reset.Location = new Point(X, y + 4); add(reset); y += 50;
            reset.Click += (s, e) => ResetLook();

            head("6. More");
            label("Batch or pill name (blank = the first one)"); tDevice = UI.Box(); put(tDevice, 38);
            label("Temperature");
            var unitBox = new Panel { Location = new Point(X, y), Size = new Size(W, 30) };
            rC = UI.Radio(UI.Deg + "C"); rC.Location = new Point(0, 2);
            rF = UI.Radio(UI.Deg + "F"); rF.Location = new Point(160, 2);
            unitBox.Controls.AddRange(new Control[] { rC, rF }); add(unitBox); y += 36;
            label("Time zone"); cTz = UI.Combo(); put(cTz, 40);
            Opt.Fill(cTz, "GMT0BST,M3.5.0/1,M10.5.0", "UK and Ireland", "CET-1CEST,M3.5.0,M10.5.0/3", "Central Europe",
                "EET-2EEST,M3.5.0/3,M10.5.0/4", "Eastern Europe", "EST5EDT,M3.2.0,M11.1.0", "US Eastern", "CST6CDT,M3.2.0,M11.1.0", "US Central",
                "MST7MDT,M3.2.0,M11.1.0", "US Mountain", "PST8PDT,M3.2.0,M11.1.0", "US Pacific", "AEST-10AEDT,M10.1.0,M4.1.0/3", "Australia (Sydney, Melbourne)",
                "AEST-10", "Australia (Brisbane)", "NZST-12NZDT,M9.5.0,M4.1.0/3", "New Zealand", "SAST-2", "South Africa", "UTC0", "UTC");
            label("Check for new readings every (minutes)");
            nRefresh = UI.Combo(); nRefresh.Width = 176;
            Opt.Fill(nRefresh, "1", "1 minute", "2", "2 minutes", "5", "5 minutes", "10", "10 minutes", "15", "15 minutes", "30", "30 minutes",
                "60", "1 hour", "120", "2 hours");
            nRefresh.Location = new Point(X, y); add(nRefresh); y += 50;
            close(); var spacer = new Label { Location = new Point(0, y), Size = new Size(10, 4) }; setScroll.Controls.Add(spacer);

            var bar = new Panel { Dock = DockStyle.Bottom, Height = 58, Padding = new Padding(0, 10, 0, 0) };
            saveBtn = UI.Btn("Save to FermFrame", true); saveBtn.AutoSize = false; saveBtn.Size = new Size(180, 40); saveBtn.Location = new Point(0, 12);
            saveBtn.Click += (s, e) => SaveSettings();
            var reload = UI.Btn("Undo my changes"); reload.AutoSize = false; reload.Size = new Size(160, 40); reload.Location = new Point(192, 12);
            reload.Click += (s, e) => { if (Sel != null) LoadSettings(Sel); };
            saveStatus = UI.Lbl("", 10, false, UI.Dim); saveStatus.Location = new Point(360, 22);
            bar.Controls.AddRange(new Control[] { saveBtn, reload, saveStatus });

            p.Controls.Add(setScroll);
            p.Controls.Add(bar);
            setScroll.BringToFront();
            return p;
        }

        // Times every 15 minutes (a clock set to another time keeps it - Opt.Select adds it)
        static ComboBox TimeCombo()
        {
            var c = UI.Combo(); c.Width = 176; c.MaxDropDownItems = 12;
            for (int m = 0; m < 24 * 60; m += 15) { var t = (m / 60).ToString("00") + ":" + (m % 60).ToString("00"); c.Items.Add(new Opt(t, t)); }
            return c;
        }

        void SourceLabels()
        {
            bool bf = rBf.Checked;
            lUser.Text = bf ? "Brewfather user ID" : "RAPT email";
            lKey.Text = bf ? "Brewfather API key  (Brewfather: Settings > API > Generate, tick Read batches)" : "RAPT API secret  (RAPT website: My account > API Secrets)";
        }

        void ResetLook()
        {
            filling = true;
            Opt.Select(cTheme, "beer");
            for (int i = 0; i < 6; i++) colours[i].BackColor = ColorTranslator.FromHtml(Themes["beer"][i]);
            Opt.Select(cFont, "digital"); trBright.Value = 100; kNight.Checked = false;
            Opt.Select(cGraph, "line"); Opt.Select(cPlot, "both"); Opt.Select(cRange, "168");
            kGraph.Checked = true; kBig.Checked = false; kClock.Checked = false; kBrews.Checked = false;
            Opt.Select(nSecs, "10"); kKeg.Checked = true;
            filling = false;
            saveStatus.Text = "Press Save to FermFrame to use these."; saveStatus.ForeColor = UI.Dim;
        }

        void LoadSettings(Clock c, bool keepStatus = false)
        {
            if (!keepStatus) { saveStatus.Text = "Reading the settings from the FermFrame..."; saveStatus.ForeColor = UI.Dim; }
            setScroll.Enabled = false; saveBtn.Enabled = false;
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var r = Net.Get(c.Base + "/", 20);
                var s = r.Status == 200 ? ClockSettings.Parse(r.Body) : null;
                BeginInvoke(new Action(() =>
                {
                    if (s != null) { c.Settings = s; if (!keepStatus) saveStatus.Text = ""; }
                    else saveStatus.Text = "Couldn't read the settings (" + (r.Error ?? "code " + r.Status) + ").";
                    if (Sel == c) { FillSettings(); ShowOverview(); }
                }));
            });
        }

        // Picks the clock's value in a list; a value that isn't in the list is added so it's kept as it is
        static void SetNum(ComboBox c, string v, int def, string unit)
        {
            int n; if (!int.TryParse(v, out n)) n = def;
            foreach (Opt o in c.Items) if (o.Value == n.ToString()) { c.SelectedItem = o; return; }
            var add = new Opt(n.ToString(), n + " " + unit); c.Items.Add(add); c.SelectedItem = add;
        }

        static void SetTime(ComboBox c, string hhmm, string def)
        {
            DateTime t;
            if (!DateTime.TryParseExact(hhmm, "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out t)) hhmm = def;
            Opt.Select(c, hhmm);
        }

        void FillSettings()
        {
            var c = Sel;
            var s = c == null ? null : c.Settings;
            bool ok = s != null && c.Online && !c.Busy;
            setScroll.Enabled = ok; saveBtn.Enabled = ok;
            if (c != null && !c.Online) setNote.Text = "This FermFrame isn't answering, so its settings can't be changed right now.";
            else if (s == null) setNote.Text = "Reading the settings from the FermFrame...";
            else if (!s.Full) setNote.Text = "This FermFrame has the setup-only version of FermFrame. Install the full version on the Firmware tab to change the look, graph and pages.";
            else setNote.Text = "Changes go to the FermFrame when you press Save to FermFrame. The FermFrame only restarts if you change the Wi-Fi.";
            if (s == null) return;

            filling = true;
            tSsid.Text = s.Get("ssid"); tPass.Text = "";
            passHint.Text = s.PassSaved ? "Leave blank to keep the saved password." : "";
            bool bf = s.Get("source", "brewfather") != "rapt";
            rBf.Checked = bf; rRapt.Checked = !bf; SourceLabels();
            tUser.Text = s.Get("user"); tKey.Text = "";
            keyHint.Text = s.SecretSaved ? "Leave blank to keep the saved key." : "";

            Opt.Select(cTheme, s.Get("theme", "beer"));
            for (int i = 0; i < 6; i++)
            {
                try { colours[i].BackColor = ColorTranslator.FromHtml(s.Get(ColourKeys[i], Themes["beer"][i])); }
                catch { colours[i].BackColor = ColorTranslator.FromHtml(Themes["beer"][i]); }
            }
            Opt.Select(cFont, s.Get("font", "digital"));
            int br; if (!int.TryParse(s.Get("bright", "100"), out br)) br = 100;
            trBright.Value = Math.Max(5, Math.Min(100, br));
            lBright.Text = "Brightness  " + trBright.Value + "%";
            kNight.Checked = s.On.Contains("night");
            SetTime(dFrom, s.Get("nFrom"), "22:00"); SetTime(dTo, s.Get("nTo"), "07:00");
            Opt.Select(cNLevel, s.Get("nLevel", "5"));
            Opt.Select(cGraph, s.Get("graph", "line")); Opt.Select(cPlot, s.Get("plot", "both")); Opt.Select(cRange, s.Get("range", "168"));
            kGraph.Checked = s.On.Contains("pgGraph"); kBig.Checked = s.On.Contains("pgBig");
            kClock.Checked = s.On.Contains("pgClock"); kBrews.Checked = s.On.Contains("pgBrews");
            SetNum(nSecs, s.Get("pageSecs"), 10, "seconds");
            kKeg.Checked = s.On.Contains("keg");
            tDevice.Text = s.Get("device");
            bool f = s.Get("unit", "C") == "F"; rF.Checked = f; rC.Checked = !f;
            Opt.Select(cTz, s.Get("tz", "GMT0BST,M3.5.0/1,M10.5.0"));
            SetNum(nRefresh, s.Get("refresh"), 5, "minutes");
            foreach (var x in lookControls) x.Enabled = s.Full;
            filling = false;
        }

        static string Hex(Color c) { return "#" + c.R.ToString("x2") + c.G.ToString("x2") + c.B.ToString("x2"); }

        void SaveSettings()
        {
            var c = Sel;
            if (c == null || c.Settings == null) return;
            var s = c.Settings;
            if (tSsid.Text.Trim() == "") { saveStatus.Text = "The Wi-Fi name can't be empty."; saveStatus.ForeColor = UI.Bad; return; }
            if (!kGraph.Checked && !kBig.Checked && !kClock.Checked && !kBrews.Checked && s.Full) kGraph.Checked = true;

            bool wifiChange = tSsid.Text.Trim() != s.Get("ssid") || tPass.Text != "";
            if (wifiChange && MessageBox.Show(this, "You changed the Wi-Fi. The FermFrame will restart and join \"" + tSsid.Text.Trim() + "\".\n\n" +
                "If the name or password is wrong it can't come back on your Wi-Fi - you'd have to fix it by scanning the code on the FermFrame's screen with your phone.\n\nSave anyway?",
                "FermFrame", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;

            var d = new Dictionary<string, string>();
            d["ssid"] = tSsid.Text.Trim();
            if (tPass.Text != "") d["pass"] = tPass.Text;
            d["source"] = rBf.Checked ? "brewfather" : "rapt";
            d["user"] = tUser.Text.Trim();
            if (tKey.Text.Trim() != "") d["secret"] = tKey.Text.Trim();
            d["device"] = tDevice.Text.Trim();
            d["unit"] = rF.Checked ? "F" : "C";
            d["tz"] = Opt.Of(cTz);
            d["refresh"] = Opt.Of(nRefresh);
            if (s.Full)
            {
                d["look"] = "1";
                d["theme"] = Opt.Of(cTheme);
                for (int i = 0; i < 6; i++) d[ColourKeys[i]] = Hex(colours[i].BackColor);
                d["font"] = Opt.Of(cFont);
                d["bright"] = trBright.Value.ToString();
                if (kNight.Checked) d["night"] = "1";
                d["nFrom"] = Opt.Of(dFrom); d["nTo"] = Opt.Of(dTo);
                d["nLevel"] = Opt.Of(cNLevel);
                d["graph"] = Opt.Of(cGraph); d["plot"] = Opt.Of(cPlot); d["range"] = Opt.Of(cRange);
                if (kGraph.Checked) d["pgGraph"] = "1";
                if (kBig.Checked) d["pgBig"] = "1";
                if (kClock.Checked) d["pgClock"] = "1";
                if (kBrews.Checked) d["pgBrews"] = "1";
                d["pageSecs"] = Opt.Of(nSecs);
                if (kKeg.Checked) d["keg"] = "1";
            }

            saveBtn.Enabled = false; saveStatus.Text = "Saving..."; saveStatus.ForeColor = UI.Dim;
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var r = Net.PostForm(c.Base + "/save", d, 20);
                BeginInvoke(new Action(() =>
                {
                    saveBtn.Enabled = true;
                    if (r.Status == 200)
                    {
                        saveStatus.Text = wifiChange ? "Saved. The FermFrame is restarting and joining the Wi-Fi." : "Saved. Have a look at your FermFrame.";
                        saveStatus.ForeColor = UI.Good;
                        c.Settings = null;
                        if (wifiChange) { c.Online = false; RefreshList(); }
                        else LoadSettings(c, true);
                        var t = new System.Windows.Forms.Timer { Interval = wifiChange ? 30000 : 8000 };
                        t.Tick += (s2, e2) => { t.Stop(); t.Dispose(); Poll(false); };
                        t.Start();
                    }
                    else { saveStatus.Text = "The FermFrame didn't save it (" + (r.Error ?? "code " + r.Status) + ")."; saveStatus.ForeColor = UI.Bad; }
                }));
            });
        }

        // ---------------- Firmware

        Label fwClock, fwApp, fwState, fwLog;
        Button fwUpdate, fwFile, fwAll;
        BusyBar fwBar;

        Panel BuildFirmware()
        {
            var p = new Panel { Dock = DockStyle.Fill };
            var card = new Card { Location = new Point(0, 0), Width = 760 };
            p.Controls.Add(card);
            int y = 20;
            Func<Control, int, Control> put = (c, h) => { c.Location = new Point(24, y); card.Controls.Add(c); y += h; return c; };
            put(UI.Lbl("Firmware", 13, true, UI.Amber), 40);
            fwClock = (Label)put(UI.Lbl("", 11), 30);
            fwApp = (Label)put(UI.Lbl("Built into this app: FermFrame " + Bundled.Version, 11, false, UI.Dim), 36);
            fwState = (Label)put(UI.Lbl("", 11, true), 44);
            var row = new FlowLayoutPanel { Size = new Size(700, 46), WrapContents = false };
            fwUpdate = UI.Btn("Update this FermFrame", true); fwUpdate.Click += (s, e) => UpdateFirmware(new[] { Sel }, null);
            fwAll = UI.Btn("Update all my FermFrames"); fwAll.Click += (s, e) => UpdateFirmware(clocks.Where(c => c.Online && !c.Busy && (Firmware.IsOlder(c.Version) || c.Build != "full")).ToArray(), null);
            fwFile = UI.Btn("Install a different .bin file..."); fwFile.Click += (s, e) => InstallFile();
            row.Controls.AddRange(new Control[] { fwUpdate, fwAll, fwFile });
            put(row, 56);
            fwBar = new BusyBar { Size = new Size(520, 6), Visible = false };
            put(fwBar, 22);
            fwLog = UI.Lbl("", 10.5f); fwLog.AutoSize = false; fwLog.Size = new Size(700, 90);
            put(fwLog, 96);
            card.Height = y;
            y = card.Bottom + 16;
            put = (c, h) => { c.Location = new Point(4, y); p.Controls.Add(c); y += h; return c; };
            put(UI.Lbl("Keep the FermFrame plugged in while it updates. It takes about a minute, then the FermFrame restarts by itself.\nYour settings stay as they are.", 9.5f, false, UI.Dim), 50);
            return p;
        }

        void ShowFirmware()
        {
            var c = Sel; if (c == null) return;
            fwClock.Text = c.Info == null ? "On this FermFrame: unknown (not answering)" : "On this FermFrame: FermFrame " + c.Version + (c.Build == "full" ? "" : " (setup-only " + c.Build + " version)");
            bool older = Firmware.IsOlder(c.Version) || (c.Info != null && c.Build != "full");
            fwState.Text = !c.Online ? "Can't update - the FermFrame isn't answering." : c.Busy ? "Updating..." : older ? "A newer version is ready to install." : "Up to date.";
            fwState.ForeColor = !c.Online ? UI.Bad : older ? UI.Amber : UI.Good;
            fwUpdate.Text = older ? "Update this FermFrame" : "Reinstall " + Bundled.Version;
            fwUpdate.Enabled = c.Online && !c.Busy;
            fwFile.Enabled = c.Online && !c.Busy;
            int behind = clocks.Count(x => x.Online && !x.Busy && (Firmware.IsOlder(x.Version) || x.Build != "full"));
            fwAll.Visible = behind > 1;
            fwAll.Text = "Update all " + behind + " FermFrames";
        }

        void InstallFile()
        {
            var c = Sel; if (c == null) return;
            using (var d = new OpenFileDialog { Filter = "Firmware (*.bin)|*.bin", Title = "Pick a FermFrame firmware file" })
            {
                if (d.ShowDialog(this) != DialogResult.OK) return;
                var bytes = File.ReadAllBytes(d.FileName);
                var name = Path.GetFileName(d.FileName);
                var chk = Firmware.Inspect(bytes);
                if (chk.Block != null)
                {
                    MessageBox.Show(this, name + " can't be installed.\n\n" + chk.Block, "FermFrame", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                    return;
                }
                string msg;
                var icon = MessageBoxIcon.Question;
                var def = MessageBoxDefaultButton.Button1;
                if (!chk.IsFermFrame)
                {
                    msg = name + " isn't FermFrame firmware.\n\nIt's for the right chip, so " + c.Name + " should still start, but it will stop being a FermFrame: " +
                          "this app won't find it or update it any more, and you'd need that firmware's own way to get FermFrame back.\n\nInstall it anyway?";
                    icon = MessageBoxIcon.Warning; def = MessageBoxDefaultButton.Button2;
                }
                else
                {
                    var what = chk.Version != "" ? "FermFrame " + chk.Version + (chk.Build == "lite" ? " (setup-only version)" : "") : "FermFrame (version unknown)";
                    msg = "Install " + what + " on " + c.Name + "?";
                    if (chk.Build == "lite") { msg += "\n\nThe setup-only version has no graph or Brewfather readings - the FermFrame will only show its setup screen until the full version goes back on."; icon = MessageBoxIcon.Warning; def = MessageBoxDefaultButton.Button2; }
                    else if (chk.Version != "" && Firmware.Older(chk.Version, c.Version)) { msg += "\n\nThis is older than the " + c.Version + " already on it."; icon = MessageBoxIcon.Warning; }
                }
                if (MessageBox.Show(this, msg, "FermFrame", MessageBoxButtons.YesNo, icon, def) != DialogResult.Yes) return;
                UpdateFirmware(new[] { c }, bytes);
            }
        }

        void FwLog(string s, Color? col = null) { fwLog.Text = s; fwLog.ForeColor = col ?? UI.Text; }

        void UpdateFirmware(Clock[] which, byte[] custom)
        {
            which = which.Where(c => c != null && c.Online && !c.Busy).ToArray();
            if (which.Length == 0) return;
            byte[] bytes;
            try { bytes = custom ?? Firmware.Full; } catch (Exception ex) { FwLog(ex.Message, UI.Bad); return; }
            foreach (var c in which) c.Busy = true;
            fwBar.Visible = true; ShowFirmware(); RefreshList(); FillSettings();

            ThreadPool.QueueUserWorkItem(_ =>
            {
                var results = new List<string>();
                bool allGood = true;
                foreach (var c in which)
                {
                    var name = c.Name;
                    Action<string> say = t => BeginInvoke(new Action(() => FwLog(t)));
                    say("Sending FermFrame to " + name + "... (about 30 seconds)");
                    var r = Net.PostFile(c.Base + "/update", "firmware", "fermframe.bin", bytes, 180);
                    if (r.Status != 200 && r.Status != 0) { results.Add(name + ": the FermFrame said no (" + r.Status + " " + r.Body.Substring(0, Math.Min(80, r.Body.Length)) + ")"); allGood = false; continue; }
                    say(name + " is restarting...");
                    Thread.Sleep(10000);
                    Dictionary<string, object> info = null;
                    var until = DateTime.Now.AddSeconds(90);
                    while (info == null && DateTime.Now < until) { info = Net.ClockInfo(c.Base, 5); if (info == null) Thread.Sleep(3000); }
                    if (info == null) { results.Add(name + ": didn't come back yet - give it a minute, or unplug it and plug it back in."); allGood = false; continue; }
                    var inf = info;
                    BeginInvoke(new Action(() => { c.Info = inf; c.Online = true; c.Checked = DateTime.Now; c.Settings = null; }));
                    results.Add(name + ": now on FermFrame " + UI.Str(info, "version") + (r.Status == 0 ? " (the upload looked unfinished, but the FermFrame came back fine)" : ""));
                }
                BeginInvoke(new Action(() =>
                {
                    foreach (var c in which) c.Busy = false;
                    fwBar.Visible = false;
                    FwLog(string.Join("\n", results), allGood ? UI.Good : UI.Bad);
                    Store.Save(clocks); RefreshList(); ShowClock();
                }));
            });
        }

        // ---------------- new clocks

        void OpenBuy()
        {
            bool setup;
            using (var f = new BuyForm()) { f.ShowDialog(this); setup = f.WantSetup; }
            if (setup) OpenSetup();
        }

        void OpenSetup()
        {
            using (var f = new SetupForm())
            {
                f.Done = (mac, ip) =>
                {
                    var c = clocks.FirstOrDefault(x => x.Mac == mac);
                    if (c == null) { c = new Clock { Mac = mac, Name = NextName() }; clocks.Add(c); }
                    c.Ip = ip; c.Settings = null;
                    Store.Save(clocks); RefreshList();
                    list.SelectedIndex = clocks.IndexOf(c);
                };
                timer.Stop();
                f.ShowDialog(this);
                timer.Start();
            }
            Poll(false);
        }
    }

    // ------------------------------------------------------------------ where to buy a clock
    // The list is built into the app (shops.json) and can be replaced from the web without a new app:
    // if build.ps1 found shops-url.txt, the app downloads that file and keeps the last good copy.

    static class Shops
    {
        static string Cache { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FermFrame", "shops.json"); } }

        static Dictionary<string, object> Valid(string text)
        {
            var j = Net.Json(text);
            return j != null && j.ContainsKey("shops") && j["shops"] is object[] ? j : null;
        }

        static Dictionary<string, object> BuiltIn()
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("shops.json"))
            using (var r = new StreamReader(s, Encoding.UTF8)) return Valid(r.ReadToEnd());
        }

        // Call on a background thread
        public static Dictionary<string, object> Load()
        {
            if (Bundled.ShopsUrl != "")
            {
                var r = Net.Get(Bundled.ShopsUrl, 6);
                var j = r.Status == 200 ? Valid(r.Body) : null;
                if (j != null)
                {
                    try { Directory.CreateDirectory(Path.GetDirectoryName(Cache)); File.WriteAllText(Cache, r.Body); } catch { }
                    return j;
                }
                try { if (File.Exists(Cache)) { var c = Valid(File.ReadAllText(Cache)); if (c != null) return c; } } catch { }
            }
            return BuiltIn();
        }

        public static List<string> List(Dictionary<string, object> d, string k)
        {
            object o;
            var a = d != null && d.TryGetValue(k, out o) ? o as object[] : null;
            return a == null ? new List<string>() : a.Select(x => UI.Esc(x)).Where(x => x != "").ToList();
        }
    }

    class BuyForm : DarkForm
    {
        public bool WantSetup;
        FlowLayoutPanel shopRow;
        Label intro, model, look, avoid, loading, shopHead, note;
        Button setup, close;

        public BuyForm()
        {
            Text = "Get another FermFrame";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            ClientSize = new Size(600, 640);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false; MinimizeBox = false;
            Font = UI.F(10.5f);

            int W = 560;
            var t = UI.Lbl("Get another FermFrame", 17, true, UI.Amber); t.Location = new Point(20, 16);
            model = UI.Lbl("", 11.5f, true); model.Location = new Point(20, 62); model.MaximumSize = new Size(W, 0);
            intro = UI.Lbl("", 10, false, UI.Dim); intro.Location = new Point(20, 92); intro.MaximumSize = new Size(W, 0);
            look = UI.Lbl("", 10, false, UI.Good); look.Location = new Point(20, 150); look.MaximumSize = new Size(W, 0);
            avoid = UI.Lbl("", 10, false, UI.Bad); avoid.Location = new Point(20, 270); avoid.MaximumSize = new Size(W, 0);
            shopHead = UI.Lbl("Shops (opens in your browser)", 11, true, UI.Amber); shopHead.Location = new Point(20, 380);
            shopRow = new FlowLayoutPanel { Location = new Point(20, 410), Size = new Size(W, 110), WrapContents = true };
            loading = UI.Lbl("Loading the shop list...", 10, false, UI.Dim); shopRow.Controls.Add(loading);
            note = UI.Lbl("Prices and stock are on the shop's site - FermFrame isn't linked to these shops and gets nothing from them.", 9, false, UI.Dim);
            note.Location = new Point(20, 528); note.MaximumSize = new Size(W, 0);
            setup = UI.Btn("It's here - set it up"); setup.Location = new Point(20, 580);
            setup.Click += (s, e) => { WantSetup = true; Close(); };
            close = UI.Btn("Close"); close.Location = new Point(240, 580);
            close.Click += (s, e) => Close();
            Controls.AddRange(new Control[] { t, model, intro, look, avoid, shopHead, shopRow, note, setup, close });
            FinishLayout();

            Shown += (s, e) => ThreadPool.QueueUserWorkItem(_ =>
            {
                Dictionary<string, object> d = null;
                try { d = Shops.Load(); } catch { }
                try { BeginInvoke(new Action(() => Fill(d))); } catch { }
            });
        }

        void Fill(Dictionary<string, object> d)
        {
            shopRow.Controls.Clear();
            if (d == null) { shopRow.Controls.Add(UI.Lbl("Couldn't load the shop list.", 10, false, UI.Bad)); return; }
            model.Text = UI.Str(d, "model");
            intro.Text = UI.Str(d, "intro");
            look.Text = "Buy one that says:\n" + string.Join("\n", Shops.List(d, "lookFor").Select(x => "  ✓  " + x));
            avoid.Text = "Not these:\n" + string.Join("\n", Shops.List(d, "avoid").Select(x => "  ✗  " + x));
            intro.Top = model.Bottom + UI.Px(8); look.Top = intro.Bottom + UI.Px(16); avoid.Top = look.Bottom + UI.Px(16);

            // Shops for this PC's country first
            string here = "";
            try { here = RegionInfo.CurrentRegion.TwoLetterISORegionName.ToUpperInvariant(); } catch { }
            var shops = ((object[])d["shops"]).OfType<Dictionary<string, object>>()
                .Select((sh, i) => new { Name = UI.Str(sh, "name"), Url = UI.Str(sh, "url"), Local = Shops.List(sh, "regions").Contains(here),
                                         Featured = UI.Str(sh, "featured").ToLowerInvariant() == "true", Order = i })
                .Where(sh => sh.Name != "" && (sh.Url.StartsWith("https://") || sh.Url.StartsWith("http://")))
                .OrderBy(sh => sh.Featured ? 0 : sh.Local ? 1 : 2).ThenBy(sh => sh.Order).ToList();
            bool first = true;
            foreach (var sh in shops)
            {
                var b = UI.Btn(sh.Name, first && (sh.Featured || sh.Local));
                b.Margin = new Padding(0, 0, 8, 8);
                b.Scale(new SizeF(UI.S, UI.S));   // made after the window was scaled
                var url = sh.Url;
                b.Click += (s, e) => { try { Process.Start(url); } catch { } };
                shopRow.Controls.Add(b);
                first = false;
            }

            // Stack everything under the lists and fit the window to it
            shopHead.Top = avoid.Bottom + UI.Px(26);
            shopRow.Top = shopHead.Bottom + UI.Px(10);
            shopRow.Height = shopRow.GetPreferredSize(new Size(shopRow.Width, 0)).Height;
            note.Top = shopRow.Bottom + UI.Px(8);
            setup.Top = close.Top = note.Bottom + UI.Px(18);
            ClientSize = new Size(ClientSize.Width, setup.Bottom + UI.Px(20));
        }
    }

    // ------------------------------------------------------------------ small text prompt

    class PromptForm : DarkForm { public void Done() { FinishLayout(); } }

    // Version, licence, no-warranty note and the licences of the parts FermFrame is built from
    class AboutForm : DarkForm
    {
        public AboutForm()
        {
            Text = "About FermFrame";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            ClientSize = new Size(620, 640);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false; MinimizeBox = false;

            var logo = UI.Lbl("FermFrame", 22, true, UI.Amber); logo.Location = new Point(24, 20);
            var ver = UI.Lbl("App " + AppInfo.Version + UI.Dot + "FermFrame firmware " + Bundled.Version + " built in", 10, false, UI.Dim); ver.Location = new Point(26, 68);
            var body = UI.Lbl(
                "Free, open-source software under the MIT licence.\n\n" +
                "No warranty: FermFrame is provided as it is. Installing firmware on a device is at your own risk and can stop it working.\n\n" +
                "Not made by or connected with GeekMagic, Brewfather or RAPT; their names belong to their owners.\n\n" +
                "The app only talks to FermFrames on your own network" + (Bundled.ShopsUrl != "" ? " and fetches the shop list from the FermFrame project page" : "") +
                ". Your FermFrames fetch your readings from Brewfather or RAPT.", 10, false, UI.Text);
            body.Location = new Point(26, 100); body.MaximumSize = new Size(570, 0);
            Controls.AddRange(new Control[] { logo, ver, body });

            var head = UI.Lbl("Built with", 11, true, UI.Amber); head.Location = new Point(26, 300);
            var box = new RichTextBox { ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = UI.Card, ForeColor = UI.Text, Font = UI.F(9), Location = new Point(26, 330), Size = new Size(568, 230), DetectUrls = false };
            box.Text = Resource("THIRD-PARTY-NOTICES.txt");
            Native.DarkScroll(box);
            Controls.AddRange(new Control[] { head, box });

            var row = new FlowLayoutPanel { Location = new Point(24, 580), Size = new Size(580, 46), WrapContents = false };
            if (Bundled.ProjectUrl != "")
            {
                var site = UI.Btn("Project page", true); site.Click += (s, e) => { try { Process.Start(Bundled.ProjectUrl); } catch { } };
                row.Controls.Add(site);
            }
            var lic = UI.Btn("MIT licence"); lic.Click += (s, e) => { box.Text = Resource("LICENSE.txt"); head.Text = "Licence"; };
            var parts = UI.Btn("Built with"); parts.Click += (s, e) => { box.Text = Resource("THIRD-PARTY-NOTICES.txt"); head.Text = "Built with"; };
            var close = UI.Btn("Close"); close.Click += (s, e) => Close();
            row.Controls.AddRange(new Control[] { lic, parts, close });
            Controls.Add(row);
            FinishLayout();
        }

        static string Resource(string name)
        {
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
                using (var r = new StreamReader(s, Encoding.UTF8)) return r.ReadToEnd();
            }
            catch { return "(missing)"; }
        }
    }

    static class Prompt
    {
        public static string Ask(IWin32Window owner, string title, string text, string value)
        {
            using (var f = new PromptForm())
            {
                f.Text = title; f.FormBorderStyle = FormBorderStyle.FixedDialog; f.StartPosition = FormStartPosition.CenterParent;
                f.MinimizeBox = false; f.MaximizeBox = false; f.ClientSize = new Size(420, 140);
                var l = UI.Lbl(text); l.Location = new Point(16, 14);
                var t = UI.Box(); t.Location = new Point(16, 42); t.Width = 388; t.Text = value;
                var ok = UI.Btn("OK", true); ok.Location = new Point(222, 88); ok.AutoSize = false; ok.Width = 88; ok.DialogResult = DialogResult.OK;
                var no = UI.Btn("Cancel"); no.Location = new Point(316, 88); no.AutoSize = false; no.Width = 88; no.DialogResult = DialogResult.Cancel;
                f.Controls.AddRange(new Control[] { l, t, ok, no });
                f.AcceptButton = ok; f.CancelButton = no;
                f.Done();
                return f.ShowDialog(owner) == DialogResult.OK ? t.Text : null;
            }
        }
    }

    // ------------------------------------------------------------------ Wi-Fi (for setting up new clocks)

    static class Wlan
    {
        [DllImport("wlanapi.dll")] static extern uint WlanOpenHandle(uint v, IntPtr r, out uint nv, out IntPtr h);
        [DllImport("wlanapi.dll")] static extern uint WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
        [DllImport("wlanapi.dll")] static extern uint WlanScan(IntPtr h, ref Guid g, IntPtr s, IntPtr ie, IntPtr r);
        [DllImport("wlanapi.dll")] static extern void WlanFreeMemory(IntPtr p);
        [DllImport("wlanapi.dll")] static extern uint WlanCloseHandle(IntPtr h, IntPtr r);

        public static void ScanAll()
        {
            try
            {
                uint nv; IntPtr h;
                if (WlanOpenHandle(2, IntPtr.Zero, out nv, out h) != 0) return;
                IntPtr list;
                if (WlanEnumInterfaces(h, IntPtr.Zero, out list) == 0)
                {
                    int n = Marshal.ReadInt32(list);
                    for (int i = 0; i < n; i++)
                    {
                        var g = (Guid)Marshal.PtrToStructure(new IntPtr(list.ToInt64() + 8 + i * 532), typeof(Guid));
                        WlanScan(h, ref g, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                    }
                    WlanFreeMemory(list);
                }
                WlanCloseHandle(h, IntPtr.Zero);
            }
            catch { }
        }

        public static string Q(string k, string v) { return k + "=\"" + v + "\""; }

        public static string Netsh(params string[] args)
        {
            var psi = new ProcessStartInfo("netsh.exe", "wlan " + string.Join(" ", args))
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.GetEncoding(CultureInfo.CurrentCulture.TextInfo.OEMCodePage)
            };
            using (var p = Process.Start(psi))
            {
                var o = p.StandardOutput.ReadToEnd();
                p.WaitForExit(20000);
                return o;
            }
        }

        public static string Field(string text, string name)
        {
            var m = Regex.Match(text, "(?m)^\\s*" + name + "\\s*:\\s*(.*)$");
            return m.Success ? m.Groups[1].Value.Trim() : null;
        }

        public class Iface { public string Name, Ssid, Profile; public bool Connected; }

        public static Iface GetIface()
        {
            var t = Netsh("show", "interfaces");
            var name = Field(t, "Name");
            if (name == null) return null;
            return new Iface { Name = name, Connected = Regex.IsMatch(t, "(?m)^\\s*State\\s*:\\s*connected"), Ssid = Field(t, "SSID"), Profile = Field(t, "Profile") };
        }

        public class Network { public string Ssid; public bool Has24; }

        public static List<Network> GetNetworks()
        {
            ScanAll();
            Thread.Sleep(4000);
            var t = Netsh("show", "networks", "mode=bssid");
            if (t.IndexOf("location", StringComparison.OrdinalIgnoreCase) >= 0) throw new Exception("LOCATION");
            var nets = new List<Network>();
            foreach (var block in Regex.Split(t, "(?m)^SSID \\d+ :").Skip(1))
            {
                var ssid = block.Split('\n')[0].Trim();
                var bands = Regex.Matches(block, "Band\\s*:\\s*([\\d.]+)").Cast<Match>().Select(m => m.Groups[1].Value).ToList();
                var chans = Regex.Matches(block, "(?m)^\\s*Channel\\s*:\\s*(\\d+)").Cast<Match>().Select(m => int.Parse(m.Groups[1].Value)).ToList();
                if (ssid != "") nets.Add(new Network { Ssid = ssid, Has24 = bands.Contains("2.4") || chans.Any(c => c <= 14) });
            }
            return nets;
        }

        public static List<string> IPv4(string iface)
        {
            var ni = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n => n.Name == iface);
            if (ni == null) return new List<string>();
            return ni.GetIPProperties().UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork).Select(a => a.Address.ToString()).ToList();
        }

        public static string SavedPassword(string profile)
        {
            try { return Field(Netsh("show", "profile", Q("name", profile), "key=clear"), "Key Content") ?? ""; } catch { return ""; }
        }

        public static List<string> Profiles()
        {
            var list = new List<string>();
            try
            {
                foreach (Match m in Regex.Matches(Netsh("show", "profiles"), "All User Profile\\s*:\\s*(.+)$", RegexOptions.Multiline))
                    list.Add(m.Groups[1].Value.Trim());
            }
            catch { }
            return list;
        }
    }

    // ------------------------------------------------------------------ setting up a new clock (same steps as the old installer)

    class SetupForm : DarkForm
    {
        public Action<string, string> Done;
        ComboBox ssidBox;
        TextBox passBox, userBox, keyBox;
        RadioButton rbBf, rbRapt;
        Label l1, l2;
        LinkLabel help;
        Button go;
        RichTextBox logBox;
        Thread worker;

        const string ClockAP = "192.168.4.1";
        const string SetupSsid = "FermFrame-Setup", OldSetupSsid = "BrewScreen-Setup", TmpPrefix = "fermframe-tmp-";
        string iface, prevProfile, clockMac;
        List<string> madeProfiles = new List<string>();
        string jSsid, jPass, jSource, jUser, jSecret;

        public SetupForm()
        {
            Text = "Set up a new FermFrame";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            ClientSize = new Size(560, 780);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false; MinimizeBox = false;
            Font = UI.F(10.5f);

            int y = 16, W = 520;
            Func<Control, int, Control> put = (c, h) => { c.Location = new Point(20, y); if (!(c is Label) || !((Label)c).AutoSize) c.Width = W; Controls.Add(c); y += h; return c; };
            put(UI.Lbl("Set up a new FermFrame", 17, true, UI.Amber), 42);
            var intro = UI.Lbl("Plug your new clock into power (no cable to this PC needed). This PC needs Wi-Fi turned on. It will leave your Wi-Fi for a minute or two while it talks to the clock.", 10, false, UI.Dim);
            intro.AutoSize = false; intro.Height = 58; put(intro, 62);

            put(UI.Lbl("1. Your home Wi-Fi", 11, true, UI.Amber), 30);
            put(UI.Lbl("Wi-Fi name", 10, false, UI.Dim), 24);
            ssidBox = new DarkCombo { Font = UI.F(10.5f) };
            put(ssidBox, 40);
            put(UI.Lbl("Wi-Fi password", 10, false, UI.Dim), 24);
            passBox = UI.Box(true); put(passBox, 42);

            put(UI.Lbl("2. Where are your readings?", 11, true, UI.Amber), 30);
            var src = new Panel { Height = 30 };
            rbBf = UI.Radio("Brewfather"); rbBf.Checked = true; rbBf.Location = new Point(2, 2);
            rbRapt = UI.Radio("RAPT Pill"); rbRapt.Location = new Point(200, 2);
            src.Controls.AddRange(new Control[] { rbBf, rbRapt }); put(src, 34);
            help = new LinkLabel { AutoSize = false, Height = 44, LinkColor = UI.Amber, ActiveLinkColor = UI.Amber, ForeColor = UI.Dim, Font = UI.F(9.5f) };
            put(help, 48);
            l1 = UI.Lbl("Brewfather user ID", 10, false, UI.Dim); put(l1, 24);
            userBox = UI.Box(); put(userBox, 38);
            l2 = UI.Lbl("Brewfather API key", 10, false, UI.Dim); put(l2, 24);
            keyBox = UI.Box(true); put(keyBox, 46);
            rbBf.CheckedChanged += (s, e) => Labels();
            help.LinkClicked += (s, e) => { try { Process.Start((string)help.Tag); } catch { } };
            Labels();

            go = UI.Btn("Set up my FermFrame", true); go.AutoSize = false; go.Height = 48; go.Font = UI.F(12.5f, true);
            put(go, 60);
            go.Click += (s, e) => Start();

            logBox = new RichTextBox { ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = UI.Card, ForeColor = UI.Text, Font = UI.F(10) };
            logBox.Height = ClientSize.Height - y - 16;
            put(logBox, 0);
            Native.DarkScroll(logBox);
            FinishLayout();

            Shown += (s, e) => LoadWifi();
            FormClosing += (s, e) =>
            {
                if (worker != null && worker.IsAlive)
                {
                    if (MessageBox.Show(this, "The FermFrame is still being set up. Stop now?\n\nThis PC will go back to your Wi-Fi.", "FermFrame", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) { e.Cancel = true; return; }
                    try { worker.Abort(); } catch { }
                }
                if (madeProfiles.Count > 0) RestoreWifi();
            };
        }

        void Labels()
        {
            if (rbBf.Checked)
            {
                l1.Text = "Brewfather user ID"; l2.Text = "Brewfather API key";
                help.Text = "Where are these? In Brewfather: Settings > API > Generate API key (tick \"Read batches\"). Click here to open Brewfather.";
                help.Tag = "https://web.brewfather.app/tabs/settings";
            }
            else
            {
                l1.Text = "RAPT email"; l2.Text = "RAPT API secret (not your password)";
                help.Text = "Where is this? On the RAPT website: My account > API Secrets > Create. Click here to open RAPT.";
                help.Tag = "https://app.rapt.io/";
            }
            help.LinkArea = new LinkArea(help.Text.IndexOf("Click here"), 10);
        }

        void LoadWifi()
        {
            ThreadPool.QueueUserWorkItem(_ =>
            {
                var i = Wlan.GetIface();
                string pass = i != null && i.Connected ? Wlan.SavedPassword(i.Profile) : "";
                var profiles = Wlan.Profiles().Where(p => !p.StartsWith(TmpPrefix)).ToList();
                BeginInvoke(new Action(() =>
                {
                    foreach (var p in profiles) if (!ssidBox.Items.Contains(p)) ssidBox.Items.Add(p);
                    if (i != null && i.Connected) { ssidBox.Text = i.Ssid; if (pass != "") passBox.Text = pass; }
                    if (i == null) Log("This PC has no Wi-Fi. Use a laptop or a PC with Wi-Fi.", "bad");
                }));
            });
            ssidBox.SelectedIndexChanged += (s, e) => { var p = Wlan.SavedPassword(ssidBox.Text); if (p != "") passBox.Text = p; };
        }

        void Log(string text, string kind = "")
        {
            if (InvokeRequired) { try { BeginInvoke(new Action(() => Log(text, kind))); } catch { } return; }
            logBox.SelectionStart = logBox.TextLength;
            logBox.SelectionColor = kind == "good" ? UI.Good : kind == "bad" ? UI.Bad : kind == "step" ? UI.Amber : UI.Text;
            logBox.AppendText(text + "\r\n");
            logBox.ScrollToCaret();
        }

        DialogResult Ask(string text)
        {
            return (DialogResult)Invoke(new Func<DialogResult>(() => MessageBox.Show(this, text, "FermFrame", MessageBoxButtons.YesNo, MessageBoxIcon.Question)));
        }

        void Start()
        {
            logBox.Clear();
            jSsid = ssidBox.Text.Trim(); jPass = passBox.Text; jSource = rbBf.Checked ? "brewfather" : "rapt";
            jUser = userBox.Text.Trim(); jSecret = keyBox.Text.Trim();
            if (jSsid == "") { Log("Type your home Wi-Fi name first.", "bad"); return; }
            if (jUser == "" || jSecret == "") { Log("Fill in your " + l1.Text + " and " + l2.Text + " first.", "bad"); return; }
            go.Enabled = false; go.Text = "Working... please wait";
            worker = new Thread(() =>
            {
                try { Run(); }
                catch (ThreadAbortException) { }
                catch (Exception ex) { Log(ex.Message, "bad"); }
                finally { try { BeginInvoke(new Action(() => { go.Enabled = true; go.Text = "Set up my FermFrame"; })); } catch { } }
            });
            worker.IsBackground = true;
            worker.Start();
        }

        // ---- Wi-Fi switching

        bool JoinOpen(string ssid, int seconds)
        {
            var prof = TmpPrefix + ssid;
            var xml = "<?xml version=\"1.0\"?><WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\"><name>" + SecurityElement(prof) +
                "</name><SSIDConfig><SSID><name>" + SecurityElement(ssid) + "</name></SSID></SSIDConfig><connectionType>ESS</connectionType><connectionMode>manual</connectionMode>" +
                "<MSM><security><authEncryption><authentication>open</authentication><encryption>none</encryption><useOneX>false</useOneX></authEncryption></security></MSM></WLANProfile>";
            var file = Path.Combine(Path.GetTempPath(), "fermframe-profile.xml");
            File.WriteAllText(file, xml);
            Wlan.Netsh("add", "profile", Wlan.Q("filename", file), Wlan.Q("interface", iface), "user=current");
            try { File.Delete(file); } catch { }
            if (!madeProfiles.Contains(prof)) madeProfiles.Add(prof);
            var until = DateTime.Now.AddSeconds(seconds);
            var asked = DateTime.MinValue;
            while (DateTime.Now < until)
            {
                if ((DateTime.Now - asked).TotalSeconds > 8) { asked = DateTime.Now; Wlan.Netsh("connect", Wlan.Q("name", prof), Wlan.Q("ssid", ssid), Wlan.Q("interface", iface)); }
                Thread.Sleep(1000);
                var i = Wlan.GetIface();
                if (i != null && i.Connected && i.Ssid == ssid && Wlan.IPv4(iface).Any(a => a.StartsWith("192.168.4."))) { Thread.Sleep(1000); return true; }
            }
            return false;
        }

        static string SecurityElement(string s) { return System.Security.SecurityElement.Escape(s); }

        bool RestoreWifi()
        {
            if (iface == null) return true;
            if (prevProfile != null) Wlan.Netsh("connect", Wlan.Q("name", prevProfile), Wlan.Q("interface", iface));
            foreach (var p in madeProfiles) Wlan.Netsh("delete", "profile", Wlan.Q("name", p));
            madeProfiles.Clear();
            if (prevProfile == null) return true;
            var until = DateTime.Now.AddSeconds(40);
            while (DateTime.Now < until)
            {
                var i = Wlan.GetIface();
                if (i != null && i.Connected && i.Profile == prevProfile && Wlan.IPv4(iface).Any(a => !a.StartsWith("169.254."))) return true;
                Thread.Sleep(1000);
            }
            return false;
        }

        // ---- finding the clock on the home network ($mac: only this clock)

        Found FindClockOnLan(string mac)
        {
            foreach (var f in Net.Scan(mac == null))
            {
                if (f.Kind == "fermframe" && (mac == null || UI.Str(f.Info, "mac") == mac)) return f;
                if (f.Kind == "stock" && mac == null) return f;
            }
            return null;
        }

        // ---- the steps

        void FlashStock(string baseUrl, string title)
        {
            var bytes = Firmware.Lite;
            if (bytes.Length > Firmware.StockMax) throw new Exception("The setup firmware is too big for a brand-new clock (" + bytes.Length + " bytes).");
            var prefix = title.Contains("스마트") ? "KR_SDP" : "SDP";
            var m = Net.Get(baseUrl + "/mac", 5);
            var mm = Regex.Match(m.Body ?? "", "^\\s*([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})\\s*$");
            if (m.Status == 200 && mm.Success) { clockMac = mm.Groups[1].Value.ToUpperInvariant(); Log("ID: " + clockMac); }
            Log("Installing FermFrame... (about 30 seconds - keep the FermFrame plugged in!)");
            var r = Net.PostFile(baseUrl + "/update_ota", "update", prefix + "-fermframe-lite.bin", bytes, 150);
            if (r.Status == 200 && r.Body.Trim() == "OK") { Log("Installed. The FermFrame is restarting.", "good"); return; }
            if (r.Status == 0) { Log("The FermFrame stopped answering after the upload (" + r.Error + ") - checking whether it worked..."); return; }
            throw new Exception("The FermFrame didn't accept the file (it said: " + r.Status + " " + r.Body + ").");
        }

        static string PosixTimeZone()
        {
            var map = new Dictionary<string, string>
            {
                { "GMT Standard Time", "GMT0BST,M3.5.0/1,M10.5.0" }, { "Greenwich Standard Time", "GMT0BST,M3.5.0/1,M10.5.0" },
                { "W. Europe Standard Time", "CET-1CEST,M3.5.0,M10.5.0/3" }, { "Central Europe Standard Time", "CET-1CEST,M3.5.0,M10.5.0/3" },
                { "Romance Standard Time", "CET-1CEST,M3.5.0,M10.5.0/3" }, { "Central European Standard Time", "CET-1CEST,M3.5.0,M10.5.0/3" },
                { "E. Europe Standard Time", "EET-2EEST,M3.5.0/3,M10.5.0/4" }, { "FLE Standard Time", "EET-2EEST,M3.5.0/3,M10.5.0/4" },
                { "GTB Standard Time", "EET-2EEST,M3.5.0/3,M10.5.0/4" },
                { "Eastern Standard Time", "EST5EDT,M3.2.0,M11.1.0" }, { "Central Standard Time", "CST6CDT,M3.2.0,M11.1.0" },
                { "Mountain Standard Time", "MST7MDT,M3.2.0,M11.1.0" }, { "Pacific Standard Time", "PST8PDT,M3.2.0,M11.1.0" },
                { "AUS Eastern Standard Time", "AEST-10AEDT,M10.1.0,M4.1.0/3" }, { "Tasmania Standard Time", "AEST-10AEDT,M10.1.0,M4.1.0/3" },
                { "E. Australia Standard Time", "AEST-10" }, { "New Zealand Standard Time", "NZST-12NZDT,M9.5.0,M4.1.0/3" },
                { "South Africa Standard Time", "SAST-2" }, { "UTC", "UTC0" },
            };
            string v;
            return map.TryGetValue(TimeZoneInfo.Local.Id, out v) ? v : "GMT0BST,M3.5.0/1,M10.5.0";
        }

        // Puts the full build on (if needed) and saves the settings
        void FinishSetup(string baseUrl, bool viaHotspot)
        {
            var info = Net.ClockInfo(baseUrl, 8);
            if (info == null) throw new Exception("The FermFrame isn't answering at " + baseUrl + ".");
            clockMac = UI.Str(info, "mac");
            if (UI.Str(info, "build") != "full" || Firmware.IsOlder(UI.Str(info, "version")))
            {
                Log("Installing the full FermFrame " + Bundled.Version + "...");
                var r = Net.PostFile(baseUrl + "/update", "firmware", "fermframe.bin", Firmware.Full, 150);
                if (r.Status != 200)
                {
                    if (UI.Str(info, "build") != "full") throw new Exception("The full version didn't install (" + r.Status + " " + r.Error + "). Keep the FermFrame near the PC and press Set up my FermFrame again.");
                    Log("Skipped updating (the FermFrame still works on its current version).");
                }
                else
                {
                    Log("The FermFrame is restarting...");
                    Thread.Sleep(12000);
                    if (viaHotspot && !JoinOpen(SetupSsid, 90)) throw new Exception("The FermFrame didn't come back after the update. Unplug it, plug it back in and press Set up again.");
                    info = null;
                    var until = DateTime.Now.AddSeconds(60);
                    while (info == null && DateTime.Now < until) { info = Net.ClockInfo(baseUrl, 5); if (info == null) Thread.Sleep(2000); }
                    if (info == null) throw new Exception("The FermFrame didn't come back after the update. Unplug it, plug it back in and press Set up again.");
                }
            }
            Log("Saving your Wi-Fi and account details on the FermFrame...");
            var fields = new Dictionary<string, string> { { "ssid", jSsid }, { "pass", jPass }, { "source", jSource }, { "user", jUser }, { "secret", jSecret }, { "tz", PosixTimeZone() } };
            var s = Net.PostForm(baseUrl + "/save", fields, 20);
            if (s.Status != 200) throw new Exception("The FermFrame didn't save the settings (code " + s.Status + ").");
            Log("Saved. The FermFrame joins your Wi-Fi and shows your brew.", "good");
        }

        void Run()
        {
            clockMac = null;
            byte[] check; try { check = Firmware.Full; check = Firmware.Lite; } catch (Exception ex) { Log(ex.Message, "bad"); return; }
            var i = Wlan.GetIface();
            if (i == null) { Log("This PC has no Wi-Fi. Use a laptop or a PC with Wi-Fi.", "bad"); return; }
            iface = i.Name;
            prevProfile = i.Connected ? i.Profile : null;

            try
            {
                Log("Looking for your FermFrame...", "step");
                var nets = Wlan.GetNetworks();
                var homeNet = nets.FirstOrDefault(n => n.Ssid == jSsid);
                if (homeNet != null && !homeNet.Has24) Log("Heads up: '" + homeNet.Ssid + "' looks like 5 GHz only. The FermFrame needs 2.4 GHz Wi-Fi.", "bad");

                var stockNet = nets.FirstOrDefault(n => Regex.IsMatch(n.Ssid, "(?i)^smart weather clock"));
                var setupNet = nets.FirstOrDefault(n => n.Ssid == SetupSsid || n.Ssid == OldSetupSsid);
                Found lan = null;
                if (stockNet == null && setupNet == null) { Log("Not broadcasting its own Wi-Fi - looking on your home network..."); lan = FindClockOnLan(null); }

                if (stockNet == null && setupNet == null && lan == null)
                {
                    Log("Can't find the clock.", "bad");
                    Log("Unplug the clock, count to 5, plug it back in, wait 30 seconds, then press Set up my FermFrame again.");
                    return;
                }

                if (stockNet != null)
                {
                    Log("Found a brand-new clock ('" + stockNet.Ssid + "'). This PC will leave your Wi-Fi for a minute or two - that's normal.", "step");
                    if (!JoinOpen(stockNet.Ssid, 45)) throw new Exception("Couldn't connect to the clock's Wi-Fi.");
                    var r = Net.Get("http://" + ClockAP + "/", 8);
                    var title = Net.TitleOf(r.Body);
                    if (!Net.IsStockTitle(title)) throw new Exception("This doesn't look like a Geekmagic Smart Weather Clock (it says '" + title + "').");
                    FlashStock("http://" + ClockAP, title);
                    Thread.Sleep(10000);
                    Log("Waiting for FermFrame to start...");
                    if (JoinOpen(SetupSsid, 90)) FinishSetup("http://" + ClockAP, true);
                    else
                    {
                        RestoreWifi();
                        Log("Looking for it on your home network...");
                        lan = FindClockOnLan(clockMac);
                        if (lan == null || lan.Kind != "fermframe") throw new Exception("Can't see FermFrame starting. Unplug the FermFrame, plug it back in, wait 30 seconds and press Set up my FermFrame again.");
                        FinishSetup(lan.Base, false);
                    }
                }
                else if (setupNet != null)
                {
                    Log("Found a FermFrame waiting to be set up. This PC will leave your Wi-Fi for a minute.", "step");
                    if (!JoinOpen(setupNet.Ssid, 45)) throw new Exception("Couldn't connect to the FermFrame's Wi-Fi.");
                    FinishSetup("http://" + ClockAP, true);
                }
                else if (lan.Kind == "stock")
                {
                    Log("Found a brand-new clock on your home network (" + lan.Ip + ").", "step");
                    FlashStock(lan.Base, lan.Title);
                    Thread.Sleep(15000);
                    Log("Waiting for FermFrame to start...");
                    object found = null;
                    var until = DateTime.Now.AddSeconds(90);
                    while (found == null && DateTime.Now < until)
                    {
                        if (Wlan.GetNetworks().Any(n => n.Ssid == SetupSsid)) found = "hotspot";
                        else { var l = FindClockOnLan(clockMac); if (l != null && l.Kind == "fermframe") found = l; }
                    }
                    if (found is string)
                    {
                        if (!JoinOpen(SetupSsid, 45)) throw new Exception("Couldn't connect to the FermFrame's Wi-Fi.");
                        FinishSetup("http://" + ClockAP, true);
                    }
                    else if (found != null) FinishSetup(((Found)found).Base, false);
                    else throw new Exception("Can't see FermFrame starting. Unplug the FermFrame, plug it back in, wait 30 seconds and press Set up my FermFrame again.");
                }
                else
                {
                    if (Ask("Found a FermFrame that's already set up at " + lan.Ip + " (ID " + UI.Str(lan.Info, "mac") + ").\n\nIf you meant to set up a NEW clock, press No, then unplug the new clock, plug it back in and try again.\n\nUpdate this FermFrame's settings?") != DialogResult.Yes)
                    { Log("Nothing changed. Unplug the new clock, plug it back in, wait 30 seconds and try again."); return; }
                    Log("Updating FermFrame at " + lan.Ip + ".", "step");
                    FinishSetup(lan.Base, false);
                }

                Log("Putting this PC back on your Wi-Fi...");
                if (!RestoreWifi()) Log("This PC didn't get back on its Wi-Fi by itself - pick it from the Wi-Fi menu.", "bad");

                Log("Checking the FermFrame on your home network...", "step");
                Found home = null;
                var until2 = DateTime.Now.AddSeconds(120);
                while (home == null && DateTime.Now < until2) { home = FindClockOnLan(clockMac); if (home == null) Thread.Sleep(5000); }
                if (home == null || home.Kind != "fermframe")
                {
                    Log("The FermFrame hasn't shown up on '" + jSsid + "' yet.", "bad");
                    Log("If its screen says \"Can't find Wi-Fi\", the Wi-Fi name or password was wrong: scan the code on the FermFrame with your phone to fix it.");
                    return;
                }
                Log("The FermFrame is on your Wi-Fi at " + home.Ip, "good");
                var mac = UI.Str(home.Info, "mac"); var ip = home.Ip;
                BeginInvoke(new Action(() => { if (Done != null) Done(mac, ip); }));
                Log("Waiting for its first readings...");
                var info = home.Info;
                var until3 = DateTime.Now.AddSeconds(60);
                while (DateTime.Now < until3)
                {
                    Thread.Sleep(10000);
                    var j = Net.ClockInfo(home.Base, 5);
                    if (j != null) { info = j; if (UI.Str(j, "problem") != "") break; }
                }
                var pr = UI.Str(info, "problem");
                if (pr != "")
                {
                    Log("The FermFrame says: " + pr, "bad");
                    if (Regex.IsMatch(pr, "wrong ID|wrong email")) Log("Check the ID and key (use the help link above), then press Set up my FermFrame again.");
                    else if (pr.Contains("Fermenting")) Log("In Brewfather, set your batch's status to Fermenting - the FermFrame picks it up within 5 minutes.");
                }
                else
                {
                    Log("All done! Your graph is on the FermFrame.", "good");
                    Log("Close this window to see the FermFrame in your list.");
                }
            }
            catch (ThreadAbortException) { throw; }
            catch (Exception ex)
            {
                if (ex.Message == "LOCATION")
                {
                    Log("Windows needs Location turned on so this program can see Wi-Fi networks.", "bad");
                    Log("Turn on \"Location services\" and \"Let desktop apps access your location\", then press Set up my FermFrame again.");
                    try { Process.Start("ms-settings:privacy-location"); } catch { }
                }
                else Log(ex.Message, "bad");
                if (prevProfile != null) RestoreWifi();
            }
        }
    }
}
