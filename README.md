# TIRC

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
