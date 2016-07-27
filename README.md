# pe-puppetdb-extensions

Closed source extensions for PuppetDB.

This includes (but is not limited too):
- Support for HA PuppetDB (replication between PDB instances). This allows users
  to have PuppetDB instances mirror each other which to support fail over.
- Support for unchanged resource storage on reports.
- Support for RBAC authentication when querying PuppetDB.

## Development

```sh
createuser -DRSP puppetdb
./test-resources/create-dev-db.sh
lein pdb
```
