# Communication content overrides

Use one `<GOAL>.json` file for reviewed line translation corrections to a generated communication source. Each entry in
`dialogue_line_translations` names an existing `dialogue_id` and numeric line `order`, then supplies the corrected `vi`.
The source builder reports an error when an override points to a missing dialogue or line. Japanese dialogue and audio
remain unchanged.
