using System.Collections.Generic;
using System.Windows.Media;

namespace ClipSyncWindows.Models
{
    /// <summary>
    /// Vector geometry of the icons the Android client draws on a history tile.
    ///
    /// These are the exact Material Filled icons the Android build uses
    /// (androidx.compose.material.icons.filled.Image / Movie / AudioFile / Description /
    /// FolderZip / InstallMobile / InsertDriveFile / TextFields), pulled out of the same
    /// library and replayed as path data. Using glyphs from two different font families — Segoe
    /// MDL2 here, Material there — made the two lists look like cousins rather than twins, which
    /// is why the numbers below are copied instead of approximated.
    ///
    /// Every geometry is frozen once: rows are rebuilt while scrolling and a mutable geometry
    /// cannot be shared between them.
    /// </summary>
    public static class MaterialIcons
    {
        private static readonly Dictionary<FileKind, Geometry> ByKind =
            new Dictionary<FileKind, Geometry>
            {
                {
                    FileKind.Image,
                    Parse("M 21 19 V 5 c 0 -1.1 -0.9 -2 -2 -2 H 5 c -1.1 0 -2 0.9 -2 2 v 14 " +
                          "c 0 1.1 0.9 2 2 2 h 14 c 1.1 0 2 -0.9 2 -2 Z " +
                          "M 8.5 13.5 l 2.5 3.01 L 14.5 12 l 4.5 6 H 5 l 3.5 -4.5 Z")
                },
                {
                    FileKind.Video,
                    Parse("M 18 4 l 2 4 h -3 l -2 -4 h -2 l 2 4 h -3 l -2 -4 H 8 l 2 4 H 7 L 5 4 H 4 " +
                          "c -1.1 0 -1.99 0.9 -1.99 2 L 2 18 c 0 1.1 0.9 2 2 2 h 16 c 1.1 0 2 -0.9 2 -2 " +
                          "V 4 h -4 Z")
                },
                {
                    FileKind.Audio,
                    Parse("M 14 2 H 6 C 4.9 2 4.01 2.9 4.01 4 L 4 20 c 0 1.1 0.89 2 1.99 2 H 18 " +
                          "c 1.1 0 2 -0.9 2 -2 V 8 L 14 2 Z " +
                          "M 16 13 h -3 v 3.75 c 0 1.24 -1.01 2.25 -2.25 2.25 S 8.5 17.99 8.5 16.75 " +
                          "c 0 -1.24 1.01 -2.25 2.25 -2.25 0.46 0 0.89 0.14 1.25 0.38 V 11 h 4 V 13 Z " +
                          "M 13 9 V 3.5 L 18.5 9 H 13 Z")
                },
                {
                    FileKind.Document,
                    Parse("M 14 2 L 6 2 c -1.1 0 -1.99 0.9 -1.99 2 L 4 20 c 0 1.1 0.89 2 1.99 2 " +
                          "L 18 22 c 1.1 0 2 -0.9 2 -2 L 20 8 l -6 -6 Z " +
                          "M 16 18 L 8 18 v -2 h 8 v 2 Z " +
                          "M 16 14 L 8 14 v -2 h 8 v 2 Z " +
                          "M 13 9 L 13 3.5 18.5 9 13 9 Z")
                },
                {
                    FileKind.Archive,
                    Parse("M 20 6 h -8 l -2 -2 H 4 C 2.9 4 2.01 4.9 2.01 6 L 2 18 c 0 1.1 0.9 2 2 2 " +
                          "h 16 c 1.1 0 2 -0.9 2 -2 V 8 C 22 6.9 21.1 6 20 6 Z " +
                          "M 18 12 h -2 v 2 h 2 v 2 h -2 v 2 h -2 v -2 h 2 v -2 h -2 v -2 h 2 v -2 " +
                          "h -2 V 8 h 2 v 2 h 2 V 12 Z")
                },
                {
                    FileKind.Package,
                    Parse("M 17 18 H 7 V 6 h 7 V 1 H 7 C 5.9 1 5 1.9 5 3 v 18 c 0 1.1 0.9 2 2 2 " +
                          "h 10 c 1.1 0 2 -0.9 2 -2 v -5 h -2 V 18 Z " +
                          "M 18 14 l 5 -5 -1.41 -1.41 -2.59 2.58 0 -7.17 -2 0 0 7.17 -2.59 -2.58 " +
                          "-1.41 1.41 Z")
                },
                {
                    // Windows 11 style folder: the front plate. The back plate is drawn under it
                    // (FolderBack) so the icon has the two layers Explorer's folder has.
                    FileKind.Folder,
                    Parse("M 2.3 10.9 C 2.3 10 3 9.3 3.9 9.3 L 20.1 9.3 C 21 9.3 21.7 10 21.7 10.9 " +
                          "L 21.7 17.1 C 21.7 18.5 20.6 19.7 19.2 19.7 L 4.8 19.7 C 3.4 19.7 2.3 18.5 " +
                          "2.3 17.1 Z")
                },
                {
                    FileKind.Other,
                    Parse("M 6 2 c -1.1 0 -1.99 0.9 -1.99 2 L 4 20 c 0 1.1 0.89 2 1.99 2 L 18 22 " +
                          "c 1.1 0 2 -0.9 2 -2 L 20 8 l -6 -6 L 6 2 Z " +
                          "M 13 9 L 13 3.5 18.5 9 13 9 Z")
                }
            };

        /// <summary>
        /// Back plate of the folder: the tab, then the strip that stays visible above the front
        /// plate. Drawn at reduced opacity by the view, so a single tinted colour still reads as
        /// two layers — the same trick the Android vector uses.
        /// </summary>
        public static Geometry FolderBack { get; } =
            Parse("M 3.4 5.6 C 3.4 4.8 4.1 4.2 4.9 4.2 L 9.4 4.2 C 9.9 4.2 10.4 4.4 10.8 4.8 " +
                  "L 11.9 6 L 19.1 6 C 19.9 6 20.6 6.6 20.6 7.4 L 20.6 12 L 3.4 12 Z");

        /// <summary>Little glyph that marks a text entry, drawn before its label.</summary>
        private static readonly Geometry TextFieldsIcon =
            Parse("M 2.5 4 v 3 h 5 v 12 h 3 V 7 h 5 V 4 H 2.5 Z " +
                  "M 21.5 9 h -9 v 3 h 3 v 7 h 3 v -7 h 3 V 9 Z");

        public static Geometry For(FileKind kind) =>
            ByKind.TryGetValue(kind, out var geometry) ? geometry : ByKind[FileKind.Other];

        public static Geometry TextFields => TextFieldsIcon;

        private static Geometry Parse(string data)
        {
            var geometry = Geometry.Parse(data);
            geometry.Freeze();
            return geometry;
        }
    }
}
