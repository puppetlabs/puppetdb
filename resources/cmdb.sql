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

CREATE TABLE resource_params (
       id BIGINT REFERENCES resources(id),
       name VARCHAR NOT NULL,
       value VARCHAR NOT NULL,
       PRIMARY KEY (id, name)
);

CREATE TABLE edges (
       source BIGINT REFERENCES resources(id),
       target BIGINT REFERENCES resources(id),
       PRIMARY KEY (source, target)
);
