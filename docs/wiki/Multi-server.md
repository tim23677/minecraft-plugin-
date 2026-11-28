# Multi-server setup with MariaDB

Supported: MariaDB 11.4 (tested). MySQL is not supported and OrderHub refuses to start against it.

1. Create an empty database and a user: `CREATE DATABASE orderhub CHARACTER SET utf8mb4; CREATE USER 'orderhub'@'%' IDENTIFIED BY '...'; GRANT ALL ON orderhub.* TO 'orderhub'@'%';`
2. On every server set `storage.type: mariadb`, `storage.mariadb.jdbc-url` and `user`. For a database that is not on the same machine append `sslMode=verify-full` or `verify-ca` to the URL.
3. Put the password into the environment variable named in `storage.mariadb.password-env` (default `ORDERHUB_DB_PASSWORD`). It is never read from the file.
4. Give every server its own `server-id` (a-z, 0-9, hyphen).
5. Start all servers. The first one creates the schema.

Orders, escrow and deliveries are shared. Notes for players (delivered items, expired orders) are stored in the database and shown on the next login on any server. Concurrent deliveries to the same order are serialized by row locks; the tests cover two servers delivering at once and a crash with recovery on the other server.

The MariaDB driver is LGPL licensed and is downloaded by Paper at the first start, it is not part of the jar. Offline servers cannot use MariaDB in 2.0.0.
