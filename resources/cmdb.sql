CREATE TABLE hosts (
       name VARCHAR PRIMARY KEY
);

CREATE TABLE classes (
       host VARCHAR REFERENCES hosts(name),
       name VARCHAR NOT NULL,
       PRIMARY KEY (host, name)
);

CREATE TABLE resources (
       hash VARCHAR(40) NOT NULL PRIMARY KEY,
       type VARCHAR NOT NULL,
       title VARCHAR NOT NULL,
       exported BOOLEAN NOT NULL
       -- leave out file
       -- leave out line
);

-- To handle searches on resource type
CREATE INDEX idx_resources_type ON resources(type);

CREATE TABLE host_resources (
       host VARCHAR REFERENCES hosts(name),
       resource VARCHAR(40) REFERENCES resources(hash),
       PRIMARY KEY (host, resource)
);

CREATE TABLE resource_params (
       resource VARCHAR(40) REFERENCES resources(hash),
       name VARCHAR NOT NULL,
       value VARCHAR NOT NULL,
       PRIMARY KEY (resource, name)
);

-- To handle joins to the resources table
CREATE INDEX idx_resource_params_resource ON resource_params(resource);

CREATE TABLE edges (
       host VARCHAR references hosts(name),
       source VARCHAR NOT NULL,
       target VARCHAR NOT NULL,
       PRIMARY KEY (host, source, target)
);
