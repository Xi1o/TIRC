# TIRC

### COMMIT NOTES [001] - Bryght 05/04/2016

__Client.java__
* Charsets, which are defined in the RFC, have been encapsulated.
* Casts in readByte conditions were removed, == supports bytes.
* Added enum OpCode and a readOpCode method to decrease confusion given the huge number of incoming different readBytes calls.
* Added a logger.
* Code fully documented.

__Context.java__
* refuseConnection now takes an error code as a parameter (cf RFC).
* Now refuses connection to clients with nicknames that are too long.
