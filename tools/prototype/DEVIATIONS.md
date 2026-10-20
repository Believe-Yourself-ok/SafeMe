# Intentional copy deviations (SAD-319)

The prototype reproduces production copy **verbatim**, including wording that
looks odd. The only exceptions are strings that are defective in
`app/src/main/res/values/strings.xml` itself; each one is listed here, mirrored
by the `DEVIATIONS` table in `verify_prototype.py`, and asserted in both
directions so this file cannot drift from the code.

| Resource | Production text | Prototype text | Why |
|---|---|---|---|
| `vpn_whitelist_count` | `%1$d app(s) exempt` | `%1$d app exempt` / `%1$d apps exempt` | literal `(s)` renders "1 app(s) exempt"; replaced with a real singular/plural pair (owner decision, SAD-319) |

Nothing else may deviate: group F fails if a production string is missing, and
group H fails if a `(s)`-style plural exists in `strings.xml` without a registry
entry.
