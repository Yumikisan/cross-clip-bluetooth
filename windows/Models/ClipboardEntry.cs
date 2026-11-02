using System;
using Newtonsoft.Json;

namespace ClipSyncWindows.Models
{
    public enum ClipKind
    {
        Text = 0,
        File = 1,

        /// <summary>A whole folder; FilePath points at the directory.</summary>
        Folder = 2
    }

    public enum ClipDirection
    {
        Local = 0,
        Incoming = 1,
        Outgoing = 2
    }

    /// <summary>
    /// One entry of the clipboard history. Entries are persisted as JSON and are
    /// intentionally tiny so that keeping a few hundred of them is cheap.
    /// </summary>
    public class ClipboardEntry
    {
        public string Id { get; set; } = Guid.NewGuid().ToString("n");

        public ClipKind Kind { get; set; } = ClipKind.Text;

        public string Text { get; set; } = "";

        public string FileName { get; set; } = "";

        public string FilePath { get; set; } = "";

        public long Size { get; set; }

        public ClipDirection Direction { get; set; } = ClipDirection.Local;

        /// <summary>Peer device name, or "本机"/"This PC" for locally copied items.</summary>
        public string Source { get; set; } = "";

        public DateTimeOffset Timestamp { get; set; } = DateTimeOffset.Now;

        [JsonIgnore]
        public string Preview
        {
            get
            {
                var raw = Kind == ClipKind.Text ? Text : FileName;
                if (string.IsNullOrEmpty(raw)) return "";
                var flat = raw.Replace("\r", " ").Replace("\n", " ").Trim();
                return flat.Length <= 160 ? flat : flat.Substring(0, 160) + "…";
            }
        }

        private bool _thumbProbed;
        private System.Windows.Media.Imaging.BitmapSource? _thumb;

        /// <summary>
        /// Decoded preview for image entries; null for everything else, when the file is gone, or
        /// when the user turned thumbnails off.
        ///
        /// The result is held on the entry: [HasThumbnail], [ShowTypeBadge] and the image binding
        /// all read this property, and every read reached [ThumbnailCache.Get], which stats the file
        /// three times before it even looks at the cache. With the card panel laying out every row
        /// at once that was several thousand file-system calls per refresh, on the UI thread.
        /// Turning thumbnails back on is handled by the window rebuilding the rows.
        /// </summary>
        [JsonIgnore]
        public System.Windows.Media.Imaging.BitmapSource? Thumbnail
        {
            get
            {
                if (Kind != ClipKind.File) return null;
                if (!Services.HistoryViewOptions.Instance.Thumbnails) return null;
                if (_thumbProbed) return _thumb;
                _thumbProbed = true;
                _thumb = Services.ThumbnailCache.Get(this);
                return _thumb;
            }
        }

        [JsonIgnore]
        public bool HasThumbnail => Thumbnail != null;

        /// <summary>"4 files" for a folder, empty otherwise.</summary>
        [JsonIgnore]
        public string ItemCountText => ItemCount > 0
            ? Services.I18n.Instance.T("lbl.folderFiles", ItemCount)
            : "";

        [JsonIgnore]
        public string SizeText
        {
            get
            {
                if (Kind == ClipKind.Text) return "";
                double b = Size;
                string[] units = { "B", "KB", "MB", "GB" };
                int i = 0;
                while (b >= 1024 && i < units.Length - 1) { b /= 1024; i++; }
                return $"{b:0.#} {units[i]}";
            }
        }

        [JsonIgnore]
        public string DirectionGlyph
        {
            get
            {
                return Direction switch
                {
                    ClipDirection.Incoming => "\uE896", // download
                    ClipDirection.Outgoing => "\uE898", // upload
                    _ => "\uE77B"                       // local
                };
            }
        }

        [JsonIgnore]
        public string KindGlyph => Kind == ClipKind.File ? "\uE8A5" : "\uE8C8";

        // Classification is cached: the list rebuilds often and the regexes are not free.
        private System.Windows.Media.Brush? _accent;
        private string? _formatLabel;

        /// <summary>Stable type key used by the per-type colour map ("text.code", "file.image").</summary>
        [JsonIgnore]
        public string AccentKey => Kind switch
        {
            ClipKind.Folder => EntryFormat.KeyFor(FileKind.Folder),
            ClipKind.File => EntryFormat.KeyFor(EntryFormat.DetectFile(FileName)),
            _ => EntryFormat.KeyFor(EntryFormat.DetectText(Text))
        };

        /// <summary>
        /// Colour of the identifier bar: text format or file kind, unless the user picked custom
        /// colours for this type (see <see cref="Services.HistoryAppearance"/>).
        /// </summary>
        [JsonIgnore]
        public System.Windows.Media.Brush AccentBrush
        {
            get
            {
                var kind = _accent ??= Kind switch
                {
                    ClipKind.Folder => EntryFormat.AccentFor(FileKind.Folder),
                    ClipKind.File => EntryFormat.AccentFor(EntryFormat.DetectFile(FileName)),
                    _ => EntryFormat.AccentFor(EntryFormat.DetectText(Text))
                };

                var settings = Services.SettingsService.Current;
                return settings == null
                    ? kind
                    : Services.HistoryAppearance.AccentFor(settings, AccentKey, kind);
            }
        }

        /// <summary>Localised label shown next to the colour bar ("Code", "Image", …).</summary>
        [JsonIgnore]
        public string FormatLabel => _formatLabel ??= Kind switch
        {
            ClipKind.Folder => Services.I18n.Instance[EntryFormat.LabelKeyFor(FileKind.Folder)],
            ClipKind.File => FileKindLabel(),
            _ => Services.I18n.Instance[EntryFormat.LabelKeyFor(EntryFormat.DetectText(Text))]
        };

        /// <summary>
        /// "Other" says nothing about an unknown file, so the extension itself takes that slot
        /// when there is one (".XYZ"); otherwise the localised generic label is used.
        /// </summary>
        private string FileKindLabel()
        {
            var tag = ExtensionTag;
            if (tag.Length > 0) return tag;
            return Services.I18n.Instance[EntryFormat.LabelKeyFor(EntryFormat.DetectFile(FileName))];
        }

        /// <summary>
        /// An unknown extension has no icon worth drawing, so the extension itself becomes the
        /// tile's content. Same rule as the Android client, and it is also reused as the row
        /// label so the two never disagree.
        /// </summary>
        [JsonIgnore]
        public string ExtensionTag
        {
            get
            {
                if (Kind == ClipKind.Text) return "";
                if (EntryFormat.DetectFile(FileName) != FileKind.Other) return "";
                var ext = System.IO.Path.GetExtension(FileName ?? "").TrimStart('.');
                return ext.Length == 0 ? "" : "." + ext.ToUpperInvariant();
            }
        }

        [JsonIgnore]
        public bool ShowExtensionTag => ExtensionTag.Length > 0;

        /// <summary>
        /// Background of the type tile: the accent colour at 14 % alpha, which is what the
        /// Android client uses for the same tile. Keeping the two in step matters more than the
        /// exact value — the tile is the first thing the eye lands on in a long list.
        ///
        /// The brush is cached per colour: it is read once per row render, and building a
        /// SolidColorBrush each time would allocate during scrolling.
        /// </summary>
        [JsonIgnore]
        public System.Windows.Media.Brush TypeBadgeBackground
        {
            get
            {
                // AccentFor returns the plain Brush type, so both paths are cast explicitly;
                // the grey fallback only matters if a theme ever hands back a gradient.
                var colour = (AccentBrush as System.Windows.Media.SolidColorBrush)?.Color
                    ?? (EntryFormat.AccentFor(FileKind.Other) as System.Windows.Media.SolidColorBrush)?.Color
                    ?? System.Windows.Media.Colors.Gray;

                if (_badgeBrush is { } cached && cached.Colour == colour) return cached.Brush;

                var brush = new System.Windows.Media.SolidColorBrush(
                    System.Windows.Media.Color.FromArgb(0x24, colour.R, colour.G, colour.B));
                brush.Freeze();
                _badgeBrush = (colour, brush);
                return brush;
            }
        }

        private (System.Windows.Media.Color Colour, System.Windows.Media.Brush Brush)? _badgeBrush;

        /// <summary>
        /// Icon of the large type tile: the very same Material geometry the Android client
        /// draws, not a lookalike from the Windows icon font.
        /// </summary>
        [JsonIgnore]
        public System.Windows.Media.Geometry TypeGeometry =>
            MaterialIcons.For(Kind == ClipKind.Folder
                ? FileKind.Folder
                : EntryFormat.DetectFile(FileName));

        /// <summary>
        /// The folder's back plate, drawn under [TypeGeometry] at reduced opacity. Null for
        /// anything that is not a folder, so the second path simply draws nothing.
        /// </summary>
        [JsonIgnore]
        public System.Windows.Media.Geometry? FolderBackGeometry =>
            Kind == ClipKind.Folder ? MaterialIcons.FolderBack : null;

        /// <summary>The extension tag takes the tile instead of the icon when there is one.</summary>
        [JsonIgnore]
        public bool ShowTypeGlyph => ShowTypeBadge && !ShowExtensionTag;

        /// <summary>Text entries carry a small glyph before their label, files do not — a file's
        /// type already lives in the tile.</summary>
        [JsonIgnore]
        public bool ShowTextGlyph => Kind == ClipKind.Text;

        [JsonIgnore]
        public bool IsFolder => Kind == ClipKind.Folder;

        /// <summary>Number of files a folder entry brought along ("4 files").</summary>
        public int ItemCount { get; set; }

        [JsonIgnore]
        public System.Windows.Media.Geometry? TextGlyphGeometry =>
            Kind == ClipKind.Text ? MaterialIcons.TextFields : null;

        /// <summary>
        /// Files get a type tile; images that already show a preview do not need one — the
        /// picture says more than the icon does.
        /// </summary>
        [JsonIgnore]
        public bool ShowTypeBadge => Kind != ClipKind.Text && !HasThumbnail;

        [JsonIgnore]
        public string TimeText => Timestamp.LocalDateTime.ToString("MM-dd HH:mm:ss");

        /// <summary>Search helper used by the history filter.</summary>
        public bool Matches(string query)
        {
            if (string.IsNullOrWhiteSpace(query)) return true;
            query = query.Trim();
            return (Text?.IndexOf(query, StringComparison.OrdinalIgnoreCase) ?? -1) >= 0
                || (FileName?.IndexOf(query, StringComparison.OrdinalIgnoreCase) ?? -1) >= 0
                || (Source?.IndexOf(query, StringComparison.OrdinalIgnoreCase) ?? -1) >= 0;
        }
    }
}
