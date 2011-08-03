CREATE TABLE hosts (
       name VARCHAR PRIMARY KEY
);

CREATE TABLE classes (
       host VARCHAR REFERENCES hosts(name),
       name VARCHAR NOT NULL,
       PRIMARY KEY (host, name)
);

CREATE TABLE resources (
       id BIGSERIAL UNIQUE,
       host VARCHAR REFERENCES hosts(name),
       type VARCHAR NOT NULL,
       title VARCHAR NOT NULL,
       exported BOOLEAN NOT NULL,
       -- leave out file
       -- leave out line
       PRIMARY KEY (host, type, title)
);

-- To handle searches on resource type
CREATE INDEX idx_resources_type ON resources(type);

CREATE TABLE resource_params (
       resource BIGINT REFERENCES resources(id),
       name VARCHAR NOT NULL,
       value VARCHAR NOT NULL,
       PRIMARY KEY (resource, name)
);

-- To handle joins to the resource table
CREATE INDEX idx_resource_params_resource ON resource_params(resource);
-- To handle lookups based on parameter value
CREATE INDEX idx_resource_params_name_value ON resource_params(name, value);

CREATE TABLE edges (
       host VARCHAR references hosts(name),
       source VARCHAR NOT NULL,
       target VARCHAR NOT NULL,
       PRIMARY KEY (host, source, target)
);
