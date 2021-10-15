# sm-async-api

FIXME: description

## Installation

Download from <http://example.com/FIXME>.

## Usage

FIXME: explanation

    java -jar sm-async-api-0.1.0-standalone.jar [args]

## Options

### Configuration files and parameters

#### sm_async.yml

Manadatory configuration file, contains basic gateway parameters

* **module_ip: hostname** - sm application server hostname and port
* **login: user-name**   -  login/password used to connect to sm server for configuration needs
* **password: password**  - password will be revomed from configuration file during startup, cifered and stored in keystore.yml
* **async-gateway-id: UID** -  moldule id,  must be unique for eache node if clustered configuration are used
* **async-gateway-port: port-number** - port number for client access
* **collections: collections-list** - list of collections presented via gateway. Each list item includes:

  * **name: collection-name** - name of collection, provided by sm and accessible via gateway
  * **acl: true/false** -  if true, accesslist configured on sm side should be applyed during authorization
  * **keylist: [ FieldName1, FieldName1]** - array of field names which formes unique key

* **max-att-size: file-size** - limit for attachment files size, bytes
* **max-total-att-size: number-of-files** - limit for number of files attached to one request, if 0 - no limits
* **mime-types: [MimeType1, MimeType2]** - list of supported mime-types
* **to-many-threads-sleep: duration** -  waiting period in ms, used to pause before retry if sm server replyed with Too-Many-Threads error
* **server-not-availalbe-sleep: duration** -  waiting period in ms, used to pause before retry if sm sever not available
* **async-pusher-enabled: true/false** - set up sync or async network communication between sm and gateway, change only if you understed perfectly what you are going to do
* **async-action-keys: [ ActionID ]** - array of field names which formes unique key for sm async request api, see Section SM Request API for detais
* **write-intermidiate-result:  true/false** - if true async gateway saves in db results of unsuccesful attempts to execute request, otherwiest only last attempt saves. Default false.
* **poll-enabled: true/false** - run poll sever, which allows sm polls requests from gateway, see Section Gateway Configuration Options for more information
* **poll-port: port-number**  - port for poll sever

#### worker.yml

Manadatory configuration file, contains parameters for push workers, whos pushing requests from gateway to sm, see Section Gateway Configuration Options for more information

* **dedicated-enabled: true/false** - enable workers dedicated for specific user or user groups
* **dedicated: workers-list**  - list worker, each item contains parameters requered by worker to start  
* **name: user-name/[user-name1, user-name2]** -  user name or array of user names whos are served by the worker
* **threads: number-of-threads** - number of request which worker allowed to place to sm server simultaneously
* **chank-size: number-of-request** - number of request read from db and buffered before send by worker
* **global-enabled: true/false**  - enable global worker to server all user having not dedicated worker
* **global-mode: masked/asis**  - in masked mode worker send request to sm using global credentials, otherwise user credentials
* **global-login: login** - login/password used to communicate with sm server by global worker in masked mode
* **global-password: password**  - password will be revomed from configuration file during startup, cifered and stored in keystore.
* **global-threads: number-of-threads** - number of request which global worker allowed to place to sm server simultaneously
* **global-chank-size: number-of-request** - number of request read from db and buffered before send by global worker
* **async-enabled: true/false** - enable "asyc" worker  to communicate with sm async request api
* **async-login: login** - login/password used to communicate with sm async request api
* **async-password: password**  - password will be revomed from configuration file during startup, cifered and stored in keystore.
* **async-threads: number-of-threads** - number of request which  "asyc" worker allowed to place to sm server simultaneously
* **async-chank-size: number-of-request** - number of request read from db and buffered before send by  "asyc" worker

#### db.yml

Manadatory configuration file, contains database connectivity  parameters

* **db-type: type**  - type of database, supported types are h2, oracle, postgersql
* **db-host: host**  - database server host and port numbe
* **db-name: name**  - database name
* **db-login: login** - login/password used to communicate with database
* **db-password: password**  - password will be revomed from configuration file during startup, cifered and stored in keystore.
* **db-schema: schema** - datbase schema name
* **h2-protocol: tcp/mem/file** - h2 specific parameter:
  * **tcp** - file stored database, h2 engine runs separately and communicate with gateway using tcp
  * **mem** - in memory database, h2 engine attached directly to gateway process
  * **file** - file stored database, h2 engine  attached directly to gateway process
* **h2-path** - database file location, make sence if h2-protocol:file only

#### messengers.yml

Manadatory configuration file, contains database connectivity parameters:

Manadatory configuration file, contains parameters for messengers, whos  send message after request processed, see Section Gateway Configuration Options for more information

* **dedicated-enabled: true/false** - enable messengers dedicated for specific user or user groups
* **dedicated: workers-list**  - list messengers, each item contains parameters requered by worker to start  
* **name: user-name/[user-name1, user-name2]** -  user name or array of user names whos are served by the worker
* **threads: number-of-threads** - number of messages which messenger allowed to send simultaneously
* **chank-size: number-of-request** - number of messages read from db and buffered before send by messenger
* **global-enabled: true/false**  - enable global messenger to server all user having not dedicated messenger
* **global-threads: number-of-threads** - number of request which global messenger allowed to send simultaneously
* **global-chank-size: number-of-request** - number of request read from db and buffered before send by global worker

#### globals.yml

Optional configuration file, allows set values for global varalble. If file missing hardcoded defauls are used.

* **new-task-waiting: duration** - idle time between examining database for new tasls and messages by workers and messagers
* **max-retry-waiting: duration** - maximum idle  time between retries for workers (if sm reply To-many-threads), real idle time is random duration between 0 and maximum idle  time
* **max-retry-waiting: duration** - idle time before next attemp in case worker or messanger got communication error
* **max-retry-count: number-of-attemp** - maximum number of attemp for:
  * copy attachment to sm server - if excided copy failed
  * push request to sm - if excided push resheduled as it specified in request
* **attachment-copy-mode: fast/slow** - in fast mode all files attached to request retrived from database by one select then send, otherwise files retrived and send one by one

## Examples

...

### Bugs

...

### Any Other Sections

### That You Think

### Might be Useful

## License

Copyright © 2021 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
<http://www.eclipse.org/legal/epl-2.0>.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at <https://www.gnu.org/software/classpath/license.html>.

# sm-async-gate
