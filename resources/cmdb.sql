CREATE TABLE certnames (
       name VARCHAR PRIMARY KEY
);

CREATE TABLE tags (
       certname VARCHAR REFERENCES certnames(name) ON DELETE CASCADE,
       name VARCHAR NOT NULL,
       PRIMARY KEY (certname, name)
);

CREATE TABLE classes (
       certname VARCHAR REFERENCES certnames(name) ON DELETE CASCADE,
       name VARCHAR NOT NULL,
       PRIMARY KEY (certname, name)
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

CREATE TABLE certname_resources (
       certname VARCHAR REFERENCES certnames(name) ON DELETE CASCADE,
       resource VARCHAR(40) REFERENCES resources(hash) ON DELETE CASCADE,
       PRIMARY KEY (certname, resource)
);

CREATE TABLE resource_params (
       resource VARCHAR(40) REFERENCES resources(hash) ON DELETE CASCADE,
       name VARCHAR NOT NULL,
       value VARCHAR NOT NULL,
       PRIMARY KEY (resource, name)
);

-- To handle joins to the resources table
CREATE INDEX idx_resource_params_resource ON resource_params(resource);

CREATE TABLE edges (
       certname VARCHAR REFERENCES certnames(name) ON DELETE CASCADE,
       source VARCHAR REFERENCES resources(hash) ON DELETE CASCADE,
       target VARCHAR REFERENCES resources(hash) ON DELETE CASCADE,
       type VARCHAR NOT NULL,
       PRIMARY KEY (certname, source, target, type)
);
