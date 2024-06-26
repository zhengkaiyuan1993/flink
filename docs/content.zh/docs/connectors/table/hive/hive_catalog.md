---
title: "Hive Catalog"
weight: 2
type: docs
aliases:
  - /zh/dev/table/connectors/hive/hive_catalog.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Hive Catalog

Hive Metastore has evolved into the de facto metadata hub over the years in Hadoop ecosystem. Many companies have a single
Hive Metastore service instance in their production to manage all of their metadata, either Hive metadata or non-Hive metadata,
 as the source of truth.
 
For users who have both Hive and Flink deployments, `HiveCatalog` enables them to use Hive Metastore to manage Flink's metadata.

For users who have just Flink deployment, `HiveCatalog` is the only persistent catalog provided out-of-box by Flink.
Without a persistent catalog, users using [Flink SQL CREATE DDL]({{< ref "docs/dev/table/sql/create" >}}) have to repeatedly
create meta-objects like a Kafka table in each session, which wastes a lot of time. `HiveCatalog` fills this gap by empowering
users to create tables and other meta-objects only once, and reference and manage them with convenience later on across sessions.


## Set up HiveCatalog

### Dependencies

Setting up a `HiveCatalog` in Flink requires the same [dependencies]({{< ref "docs/connectors/table/hive/overview" >}}#dependencies) 
as those of an overall Flink-Hive integration.

### Configuration

Setting up a `HiveCatalog` in Flink requires the same [configuration]({{< ref "docs/connectors/table/hive/overview" >}}#connecting-to-hive) 
as those of an overall Flink-Hive integration.


## How to use HiveCatalog

Once configured properly, `HiveCatalog` should just work out of box. Users can create Flink meta-objects with DDL, and should
see them immediately afterwards.

`HiveCatalog` can be used to handle two kinds of tables: Hive-compatible tables and generic tables. Hive-compatible tables
are those stored in a Hive-compatible way, in terms of both metadata and data in the storage layer. Therefore, Hive-compatible tables
created via Flink can be queried from Hive side.

Generic tables, on the other hand, are specific to Flink. When creating generic tables with `HiveCatalog`, we're just using
HMS to persist the metadata. While these tables are visible to Hive, it's unlikely Hive is able to understand
the metadata. And therefore using such tables in Hive leads to undefined behavior.

It's recommended to switch to [Hive dialect]({{< ref "docs/dev/table/hive-compatibility/hive-dialect/overview" >}}) to create Hive-compatible tables.
If you want to create Hive-compatible tables with default dialect, make sure to set `'connector'='hive'` in your table properties, otherwise
a table is considered generic by default in `HiveCatalog`. Note that the `connector` property is not required if you use Hive dialect.

### Example

We will walk through a simple example here.

#### step 1: set up a Hive Metastore

Have a Hive Metastore running. 

Here, we set up a local Hive Metastore and our `hive-site.xml` file in local path `/opt/hive-conf/hive-site.xml`.
We have some configs like the following:

```xml

<configuration>
   <property>
      <name>javax.jdo.option.ConnectionURL</name>
      <value>jdbc:mysql://localhost/metastore?createDatabaseIfNotExist=true</value>
      <description>metadata is stored in a MySQL server</description>
   </property>

   <property>
      <name>javax.jdo.option.ConnectionDriverName</name>
      <value>com.mysql.jdbc.Driver</value>
      <description>MySQL JDBC driver class</description>
   </property>

   <property>
      <name>javax.jdo.option.ConnectionUserName</name>
      <value>...</value>
      <description>user name for connecting to mysql server</description>
   </property>

   <property>
      <name>javax.jdo.option.ConnectionPassword</name>
      <value>...</value>
      <description>password for connecting to mysql server</description>
   </property>

   <property>
       <name>hive.metastore.uris</name>
       <value>thrift://localhost:9083</value>
       <description>IP address (or fully-qualified domain name) and port of the metastore host</description>
   </property>

   <property>
       <name>hive.metastore.schema.verification</name>
       <value>true</value>
   </property>

</configuration>
```


Test connection to the HMS with Hive Cli. Running some commands, we can see we have a database named `default` and there's no table in it.


```bash

hive> show databases;
OK
default
Time taken: 0.032 seconds, Fetched: 1 row(s)

hive> show tables;
OK
Time taken: 0.028 seconds, Fetched: 0 row(s)
```


#### step 2: start SQL Client, and create a Hive catalog with Flink SQL DDL

Add all Hive dependencies to `/lib` dir in Flink distribution, and create a Hive catalog in Flink SQL CLI as following:

```bash

Flink SQL> CREATE CATALOG myhive WITH (
  'type' = 'hive',
  'hive-conf-dir' = '/opt/hive-conf'
);

```


#### step 3: set up a Kafka cluster

Bootstrap a local Kafka cluster with a topic named "test", and produce some simple data to the topic as tuple of name and age.

```bash

localhost$ bin/kafka-console-producer.sh --broker-list localhost:9092 --topic test
>tom,15
>john,21

```


These message can be seen by starting a Kafka console consumer.

```bash
localhost$ bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic test --from-beginning

tom,15
john,21

```


#### step 4: create a Kafka table with Flink SQL DDL

Create a simple Kafka table with Flink SQL DDL, and verify its schema.

```bash
Flink SQL> USE CATALOG myhive;

Flink SQL> CREATE TABLE mykafka (name String, age Int) WITH (
   'connector' = 'kafka',
   'topic' = 'test',
   'properties.bootstrap.servers' = 'localhost:9092',
   'properties.group.id' = 'testGroup',
   'scan.startup.mode' = 'earliest-offset',
   'format' = 'csv'
);
[INFO] Table has been created.

Flink SQL> DESCRIBE mykafka;
root
 |-- name: STRING
 |-- age: INT

```

Verify the table is also visible to Hive via Hive Cli:

```bash
hive> show tables;
OK
mykafka
Time taken: 0.038 seconds, Fetched: 1 row(s)

```


#### step 5: run Flink SQL to query the Kafka table

Run a simple select query from Flink SQL Client in a Flink cluster, either standalone or yarn-session.

```bash
Flink SQL> select * from mykafka;

```


Produce some more messages in the Kafka topic

```bash
localhost$ bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic test --from-beginning

tom,15
john,21
kitty,30
amy,24
kaiky,18

```


You should see results produced by Flink in SQL Client now, as:


```bash
             SQL Query Result (Table)
 Refresh: 1 s    Page: Last of 1     

        name                       age
         tom                        15
        john                        21
       kitty                        30
         amy                        24
       kaiky                        18

```

## Supported Types

`HiveCatalog` supports all Flink types for generic tables.

For Hive-compatible tables, `HiveCatalog` needs to map Flink data types to corresponding Hive types as described in
the following table:

<table class="table table-bordered">
  <thead>
    <tr>
      <th class="text-center" style="width: 25%">Flink Data Type</th>
      <th class="text-center">Hive Data Type</th>
    </tr>
  </thead>
  <tbody>
    <tr>
        <td class="text-center">CHAR(p)</td>
        <td class="text-center">CHAR(p)</td>
    </tr>
    <tr>
        <td class="text-center">VARCHAR(p)</td>
        <td class="text-center">VARCHAR(p)</td>
    </tr>
        <tr>
        <td class="text-center">STRING</td>
        <td class="text-center">STRING</td>
    </tr>
    <tr>
        <td class="text-center">BOOLEAN</td>
        <td class="text-center">BOOLEAN</td>
    </tr>
    <tr>
        <td class="text-center">TINYINT</td>
        <td class="text-center">TINYINT</td>
    </tr>
    <tr>
        <td class="text-center">SMALLINT</td>
        <td class="text-center">SMALLINT</td>
    </tr>
    <tr>
        <td class="text-center">INT</td>
        <td class="text-center">INT</td>
    </tr>
    <tr>
        <td class="text-center">BIGINT</td>
        <td class="text-center">LONG</td>
    </tr>
    <tr>
        <td class="text-center">FLOAT</td>
        <td class="text-center">FLOAT</td>
    </tr>
    <tr>
        <td class="text-center">DOUBLE</td>
        <td class="text-center">DOUBLE</td>
    </tr>
    <tr>
        <td class="text-center">DECIMAL(p, s)</td>
        <td class="text-center">DECIMAL(p, s)</td>
    </tr>
    <tr>
        <td class="text-center">DATE</td>
        <td class="text-center">DATE</td>
    </tr>
    <tr>
        <td class="text-center">TIMESTAMP(9)</td>
        <td class="text-center">TIMESTAMP</td>
    </tr>
    <tr>
        <td class="text-center">BYTES</td>
        <td class="text-center">BINARY</td>
    </tr>
    <tr>
        <td class="text-center">ARRAY&lt;T&gt;</td>
        <td class="text-center">LIST&lt;T&gt;</td>
    </tr>
    <tr>
        <td class="text-center">MAP<K, V></td>
        <td class="text-center">MAP<K, V></td>
    </tr>
    <tr>
        <td class="text-center">ROW</td>
        <td class="text-center">STRUCT</td>
    </tr>
  </tbody>
</table>

Something to note about the type mapping:
* Hive's `CHAR(p)` has a maximum length of 255
* Hive's `VARCHAR(p)` has a maximum length of 65535
* Hive's `MAP` only supports primitive key types while Flink's `MAP` can be any data type
* Hive's `UNION` type is not supported
* Hive's `TIMESTAMP` always has precision 9 and doesn't support other precisions. Hive UDFs, on the other hand, can process `TIMESTAMP` values with a precision <= 9.
* Hive doesn't support Flink's `TIMESTAMP_WITH_TIME_ZONE`, `TIMESTAMP_WITH_LOCAL_TIME_ZONE`, and `MULTISET`
* Flink's `INTERVAL` type cannot be mapped to Hive `INTERVAL` type yet
