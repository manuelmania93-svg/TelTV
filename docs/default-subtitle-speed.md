# Downloaded-subtitle timing default

New downloaded subtitles start at the requested `23.976>25` preset (`23.976 / 25`, about x0.959). This applies to both automatic downloads and manual attachment through the online-subtitle picker. It is a user-selected default, **not frame-rate detection**. It may be wrong for already-synchronized subtitles; Reset selects 1x.

Saved timing wins for the exact logical video and subtitle-content fingerprint. Reset now stores identity (`0;1.0`) instead of deleting the entry, so reopening the same video/subtitle does not reapply the default. Reset speed alone preserves an existing offset. A different subtitle content fingerprint or video starts with the default. Old identity resets were deleted by older builds and cannot be recovered, so users may need to Reset those once again.

Embedded subtitles, video playback speed, language preferences, existing automatic-download behavior and ambiguous/wrong-episode selection guards are unchanged. No guarantee that a suitable German subtitle exists. No unconditional auto-download bypass, remote fps lookup, voice analysis or new network request is introduced.

The speed presets are full-width buttons on separate rows; +/- and Reset occupy a third row. JVM policy tests, real DataStore persistence/isolation tests and a remote-operated Compose layout test cover the change. CI and pixel inspection must be checked before merging. Not tested on the physical TV.
