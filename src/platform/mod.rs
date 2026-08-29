//! Per-platform ROM registry.
//!
//! Each console/ecosystem gets its own `Platform` with the ROM file extensions it
//! understands. This is the (P2) `platform` core from the architecture doc: extension
//! lists that used to be hard-coded in the GUI scanner / import / bridge now live here
//! in one place, and the GUI + CLI can assign files to per-platform library folders.
//!
//! Detection is extension-based (with a per-folder override fallback for ambiguous
//! disc images like `.iso`/`.bin`/`.chd` that are shared across many platforms).

pub mod organize;

use std::path::Path;

/// A console / ecosystem plus the file extensions that belong to it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Platform {
    Switch,
    Psx,
    Ps2,
    Ps3,
    Psp,
    PsVita,
    N64,
    GameCube,
    Gameboy,
    GameboyColor,
    GameboyAdvance,
    NintendoDs,
    ThreeDs,
    Snes,
    Nes,
    SegaGenesis,
    SegaSaturn,
    SegaDreamcast,
    Atari2600,
}

impl Platform {
    /// Stable programmatic id (used for folder names / keys).
    pub fn id(self) -> &'static str {
        match self {
            Platform::Switch => "switch",
            Platform::Psx => "psx",
            Platform::Ps2 => "ps2",
            Platform::Ps3 => "ps3",
            Platform::Psp => "psp",
            Platform::PsVita => "psvita",
            Platform::N64 => "n64",
            Platform::GameCube => "gamecube",
            Platform::Gameboy => "gameboy",
            Platform::GameboyColor => "gameboy-color",
            Platform::GameboyAdvance => "gba",
            Platform::NintendoDs => "nds",
            Platform::ThreeDs => "3ds",
            Platform::Snes => "snes",
            Platform::Nes => "nes",
            Platform::SegaGenesis => "genesis",
            Platform::SegaSaturn => "saturn",
            Platform::SegaDreamcast => "dreamcast",
            Platform::Atari2600 => "atari-2600",
        }
    }

    /// Human-friendly display / folder name.
    pub fn name(self) -> &'static str {
        match self {
            Platform::Switch => "Switch",
            Platform::Psx => "PlayStation",
            Platform::Ps2 => "PlayStation 2",
            Platform::Ps3 => "PlayStation 3",
            Platform::Psp => "PSP",
            Platform::PsVita => "PS Vita",
            Platform::N64 => "Nintendo 64",
            Platform::GameCube => "GameCube",
            Platform::Gameboy => "Game Boy",
            Platform::GameboyColor => "Game Boy Color",
            Platform::GameboyAdvance => "Game Boy Advance",
            Platform::NintendoDs => "Nintendo DS",
            Platform::ThreeDs => "3DS",
            Platform::Snes => "Super Nintendo",
            Platform::Nes => "NES",
            Platform::SegaGenesis => "Sega Genesis",
            Platform::SegaSaturn => "Sega Saturn",
            Platform::SegaDreamcast => "Sega Dreamcast",
            Platform::Atari2600 => "Atari 2600",
        }
    }

    /// ROM file extensions this console uses (without leading dots).
    pub fn rom_extensions(self) -> &'static [&'static str] {
        match self {
            Platform::Switch => &["nsp", "nsx", "nsz", "xci", "xcz", "nca", "ncz"],
            Platform::Psx => &["bin", "cue", "img", "pbp", "chd", "iso", "ecm"],
            Platform::Ps2 => &["iso", "cso", "zso", "chd", "bin", "img"],
            Platform::Ps3 => &["iso", "pkg", "jb", "bdmv"],
            Platform::Psp => &["iso", "cso", "pbp", "zso"],
            Platform::PsVita => &["vpk", "mai", "pkg"],
            Platform::N64 => &["n64", "z64", "v64", "rom"],
            Platform::GameCube => &["gcm", "iso", "gcz", "rvz"],
            Platform::Gameboy => &["gb", "dmg"],
            Platform::GameboyColor => &["gbc"],
            Platform::GameboyAdvance => &["gba"],
            Platform::NintendoDs => &["nds"],
            Platform::ThreeDs => &["3ds", "cia", "cci"],
            Platform::Snes => &["sfc", "smc", "fig"],
            Platform::Nes => &["nes", "fds", "unf", "unif"],
            Platform::SegaGenesis => &["md", "gen", "smd", "bin"],
            Platform::SegaSaturn => &["iso", "cue", "bin", "chd"],
            Platform::SegaDreamcast => &["gdi", "cdi", "iso", "chd"],
            Platform::Atari2600 => &["a26", "bin"],
        }
    }

    /// Extensions shared across many disc platforms. These cannot be resolved to a
    /// single platform by extension alone, so they also need a per-folder hint/override.
    pub fn is_ambiguous_extension(ext: &str) -> bool {
        matches!(ext, "iso" | "bin" | "chd")
    }

    /// Resolve a platform by file extension, using `folder_hint` (last path component
    /// of the enclosing folder) to disambiguate shared disc extensions.
    ///
    /// Order: explicit folder-name override first, then unambiguous extension match,
    /// then a best-effort match for shared extensions (preferring the disc consoles).
    pub fn detect(path: &Path) -> Option<Platform> {
        let ext = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        if ext.is_empty() {
            return None;
        }

        let folder_hint = path
            .parent()
            .and_then(|p| p.file_name())
            .and_then(|n| n.to_str())
            .map(|s| s.to_ascii_lowercase());

        // Explicit folder-name override wins (e.g. a folder literally called "psx").
        if let Some(hint) = folder_hint {
            for platform in Platform::all() {
                if platform
                    .name()
                    .to_ascii_lowercase()
                    .replace(' ', "-")
                    == hint
                    || platform.id() == hint
                {
                    return Some(platform);
                }
            }
        }

        Platform::all()
            .iter()
            .copied()
            .find(|p| p.rom_extensions().contains(&ext.as_str()))
            .or_else(|| {
                // For truly ambiguous shared extensions with no folder hint, prefer
                // a sensible disc-platform default and let the user override in GUI.
                if Platform::is_ambiguous_extension(&ext) {
                    Some(Platform::Psx)
                } else {
                    None
                }
            })
    }

    pub fn all() -> [Platform; 19] {
        [
            Platform::Switch,
            Platform::Psx,
            Platform::Ps2,
            Platform::Ps3,
            Platform::Psp,
            Platform::PsVita,
            Platform::N64,
            Platform::GameCube,
            Platform::Gameboy,
            Platform::GameboyColor,
            Platform::GameboyAdvance,
            Platform::NintendoDs,
            Platform::ThreeDs,
            Platform::Snes,
            Platform::Nes,
            Platform::SegaGenesis,
            Platform::SegaSaturn,
            Platform::SegaDreamcast,
            Platform::Atari2600,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::Platform;
    use std::path::Path;

    #[test]
    fn detects_switch_extensions() {
        assert_eq!(
            Platform::detect(Path::new("/games/Zelda.nsp")),
            Some(Platform::Switch)
        );
        assert_eq!(
            Platform::detect(Path::new("/games/Mario.xci")),
            Some(Platform::Switch)
        );
    }

    #[test]
    fn detects_cartridge_extensions() {
        assert_eq!(
            Platform::detect(Path::new("/games/Pokemon.gba")),
            Some(Platform::GameboyAdvance)
        );
        assert_eq!(
            Platform::detect(Path::new("/games/Mario.sfc")),
            Some(Platform::Snes)
        );
        assert_eq!(
            Platform::detect(Path::new("/games/DonkeyKong.nes")),
            Some(Platform::Nes)
        );
        assert_eq!(
            Platform::detect(Path::new("/games/Metroid.nds")),
            Some(Platform::NintendoDs)
        );
    }

    #[test]
    fn ambiguous_iso_defaults_to_psx_without_folder_hint() {
        assert_eq!(
            Platform::detect(Path::new("/import/tmp.iso")),
            Some(Platform::Psx)
        );
    }

    #[test]
    fn folder_hint_disambiguates_shared_extensions() {
        assert_eq!(
            Platform::detect(Path::new("/library/PS2/GTA.iso")),
            Some(Platform::Ps2)
        );
        assert_eq!(
            Platform::detect(Path::new("/library/PSP/MonsterHunter.iso")),
            Some(Platform::Psp)
        );
    }

    #[test]
    fn unknown_extension_is_none() {
        assert_eq!(Platform::detect(Path::new("/games/readme.txt")), None);
        assert_eq!(Platform::detect(Path::new("/games/noext")), None);
    }
}