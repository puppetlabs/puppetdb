release_notes/release_notes_latest.markdown

## PuppetDB 6.9.1

### New features 
 - New `initial-report-threshold` configuration option in sync settings. On startup, PuppetDB will only sync reports newer than the threshold. Older reports will still be transferred on subsequent periodic syncs. [PDB-3751](https://tickets.puppetlabs.com/browse/PDB-3751)
