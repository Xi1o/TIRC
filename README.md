# TIRC

### Commit notes [003] - Bryght 12/04/2016

__RFC__
* Packet `opcode=7` : SERV gives CLIENT1 information about CLIENT2
    ```java
	+--------+------+---------+---------+
	| opcode | port | IP size | address |
	+--------+------+---------+---------+
	|   7    | int  |   int   |  bytes  |
	+--------+------+---------+---------+
    ```
    * We don't need to know if the client exists or not (client's responsability).
    * An IP Address is encoded as a sequence of bytes. It will always be preceded by the size of the address, coded in an int.
    * `IPv4 = 32 bits = 4 bytes`
    * `IPv6 = 128 bits = 16 bytes`

__Client.java__
* `processInput()` :
    * Now parses commands (`args` style), to make the `switch` code easier for commands with arguments.
    * Is now safer to use for developpers : all cases end with `break`, forget about the `return` thing.
* Renamed `/w` command to `/private`. Because `/w` will be used later for private messages.
    * Syntax : `/private nickname` requests a private communication to `nickname`.
    * Client is now able to write packets `opcode=6` to the server.
    * Client is now able to read packets `opcode=7`from the server.
* Added method `packetClientInfoRequest()`.
* Added method `receiveClientInfoReply()`. Added method `readAddress()`.
* Planned naming convention :
    ```
    OPCODE  NAMING                      STATUS
    6       packetClientInfoRequest()   done
    7       receiveClientInfoReply()    working sysout tests
    8       packetPrivateComRequest()   todo
    9       receivePrivateComReply()    todo
    ```

__Context.java__
* Added packet `opcode=6` to the supported received packets. (__+ CommandReader.java__)
* Added method `receivedClientInfoRequest()` : server retrieves the destination nickname, gets the destination's `Context`, then gets the port and IP from that `Context`, and build the reply to the client.

__Server.java__
* Added method `getContextByNickname()`.

### Commit notes [002] - Bryght 11/04/2016

__ClientGUI.java__
* New class for a simple graphical user interface.
* ClientGUI will now retrieve user inputs, and give them to Client with this method. As ClientGUI is event based, the `while (!hasQuit && scanner.hasNextLine())` is no longer needed.

__Client.java__
* Added `ClientGUI` as a private field.
* Anytime you want to use `System.out.println()` for the chat, now use `ClientGUI.println()` instead. Easy.
* `getInput()` method was removed.
* `processInput()` was added. It is called by ClientGUI when an user input event was triggered.
* leaved -> left


### Commit notes [001] - Bryght 05/04/2016

__Client.java__
* Charsets, which are defined in the RFC, have been encapsulated.
* Casts in readByte conditions were removed, == supports bytes.
* Added enum OpCode and a readOpCode method to decrease confusion given the huge number of incoming different readBytes calls.
* Added a logger.
* Code fully documented.

__Context.java__
* refuseConnection now takes an error code as a parameter (cf RFC).
* Now refuses connection to clients with nicknames that are too long.
