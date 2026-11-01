# Languages and design

## Design: "Ministry Formal"

- Light, calm layout with a deep green (`#0f5c46`) ministry band across the top, a white
  sidebar and white cards. Colour is used mainly for status badges (always icon + text).
- Dark mode follows the operating system and has its own tuned palette.
- Fonts are bundled with the app (no internet needed): **Public Sans** for Latin text and
  **Noto Sans Arabic** for Pashto and Dari (covers all Pashto letters: ټ ډ ړ ږ ښ ګ ڼ ې ۍ).
- Styles live in `frontend/src/styles.css`; Bootstrap's primary colour is mapped to the ministry green.

## Languages

| Code | Language | Direction | Numbers and dates |
|---|---|---|---|
| `en` | English | left-to-right | Latin digits, Gregorian calendar |
| `ps` | پښتو (Pashto) | right-to-left | Eastern Arabic-Indic digits (۱۲۳), Solar Hijri calendar |
| `fa` | دری (Dari) | right-to-left | Eastern Arabic-Indic digits (۱۲۳), Solar Hijri calendar with month names |

- The language switcher is in the green top band (and on the sign-in and setup pages).
  The choice is remembered in the browser; the first visit follows the browser language.
- Switching to Pashto or Dari sets `<html dir="rtl">` and loads Bootstrap's official RTL
  stylesheet, so the sidebar moves to the right, tables and forms mirror, and arrows flip.
- **Technical values stay left-to-right and in Latin characters** in every language:
  IP addresses, hostnames, usernames, emails, request IDs and audit action codes.
- Dates are stored in UTC and shown in the reader's local time. Hovering a date shows the exact UTC value.
- Chrome has no built-in Pashto date data, so Pashto dates are shown numerically
  (for example `۱۴۰۵/۰۸/۱۰، ۰۷:۳۹`) using the Afghan calendar.
- Backend error messages are translated in the browser from their error `code`; unknown
  codes fall back to the server's English text.

## Translation files

`frontend/src/i18n/en.js` is the master list. `ps.js` and `fa.js` must contain every key with
the same `{placeholders}`; the test `src/test/I18n.test.jsx` fails if any key is missing.

To change a wording, edit the value in the language file. To add a new text, add the key to
all three files and use `t('your.key')` in the component.

> The Pashto and Dari texts were written for this project and should be reviewed by a native
> speaker on the ministry team, especially technical terms (for example "agent", "audit",
> "environment"), before the first production release.
