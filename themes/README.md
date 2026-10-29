# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour |
| `six-columns/` | Six smaller tiles per row and one grid row visible under the dock |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; missing apps are skipped |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `columns` | 1 to 8 | Dock slots; tiles shrink to fit from 5 up |
| `grid_columns` | 1 to 8 | Tiles per grid row below the dock; same as `columns` if left out |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock |
| `dock_opacity` | 0 to 100 | How opaque the dock band's glass is over the photo or wallpaper: 100 is the solid frosted band, lower shows more of the picture (55 by default) |
| `shamsi_date` | bool | Show the Solar Hijri date above the Gregorian one |
| `dream_athkar` | bool | Adhkar at the bottom of the Clock screensaver, a new one every minute |
| `home_prayer` | bool | Next prayer and a countdown in a glass pill under the clock, at the weather coordinates (Aladhan) |
| `dream_analog` | bool | An analog clock on the Clock screensaver instead of the digital one |
| `dream_prayer` | bool | Today's prayer times on the Clock screensaver, the next one lit |
| `prayer_method` | number | Aladhan calculation method, e.g. 21 Morocco, 3 Muslim World League, 2 ISNA |
| `dream_photos` | bool | Photos of the day (Bing) behind the Clock screensaver, a new one every 3 minutes |
| `now_playing` | bool | The song a music app is playing: a pill on the home screen and a panel on the screensaver |
| `module_cards` | bool | Look for module cards and ask about them (`true`); `false` turns the feature off |
| `weather_latitude`, `weather_longitude`, `weather_place` | string | Weather line above the date (Open-Meteo); an empty latitude hides it |
| `vpn_app` | string | App the network pill opens when a VPN is configured; also shows a red "VPN off" badge while no VPN is up |
| `show_home_settings_shortcut` | bool | The "Default home" tile, where the TV has a home picker |
| `background_color` | `#RRGGBB` | Colour behind everything; black by default |
| `wallpaper` | bool | Show `wallpaper.jpg` from the theme folder (`true`), or ignore it (`false`) |

## Wallpapers

`wallpaper.jpg` is drawn full screen behind the launcher, centre-cropped. Keep it dark, 1920×1080 and
under about 300 KB: the text, the hero name and the dock band are designed for black, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are downsampled.

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.
