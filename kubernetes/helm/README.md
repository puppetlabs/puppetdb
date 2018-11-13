# puppetdb in Kubernetes via Helm

Deploy PuppetDB with PostgreSQL in Kubernetes via helm.

This Helm chart is provided as-is with no expressed or implied warranty. This is an early prototype and not intended for deployment in production environments.

## Building the Helm package

```bash
helm package puppetdb
```

## Running

Replace the `6.0.4` below with the version output by the build command.

```bash
helm install puppetdb-6.0.4.tgz
```

## Deleting

```bash
helm list
```

Identify your PuppetDB installation. It will have a generated `name`, like `nordic-kitten` below.

    $ helm list
    NAME         	REVISION	UPDATED                 	STATUS  	CHART         	APP VERSION	NAMESPACE
    nordic-kitten	1       	Thu Nov 15 11:46:49 2018	DEPLOYED	puppetdb-6.0.4	6.0.4      	default 

```bash
helm delete nordic-kitten
```

To remove the volume that was created for PostgreSQL:

```bash
kubectl delete pvc nordic-kitten-postgresql
```
