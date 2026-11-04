# Built-in wallpapers not shipped

Upstream Glass Home's twelve built-in photos, moved out of `app/wallpapers/`
on 2026-11-01: the 2018 TV had 2 MB of storage free and couldn't install the
5.4 MB package. Roche Home ships no photos; they come from the Pi (see
`tools/sky-photos`), and the stage's dark gradient shows when it can't be
reached. To ship some again, put them back in `app/wallpapers/` with a
`manifest.json` and restore the local request in `loadManifest` (`app/js/home.js`).
