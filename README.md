## PearlPlus

PearlPlus lets you allow players at your base to load pearls through chat whispers without granting any other permissions. You can configure which pearls people can load using commands through discord or console. The config is saved to `plugins/config/pearl-plus.json`

### Management Commands

#### You can use either `pp` or `pearl+`

```bash
pearl+ toggle <on/off>
```
```bash
pearl+ allow <playerName> <pearlName>
```
```bash
pearl+ deny <playerName> <pearlName>
```
```bash
pearl+ list
```

### Usage

Setup regular pearlloader positions using the built in zenithproxy module. E.g `pearlLoader add <id> <x> <y> <z>`. Add a user to pearl+'s config using `pearl+ allow <username> <id>`. That player can now whisper "load" to the zenith bot and the bot will load the pearl. The first pearl added to a user is loaded when the whisper is sent. Players can add the pearl id after "load" to have a specific pearl loaded.
```bash
/whisper <botName> load <optionalID> 
```

### Building The Plugin

Clone the repo or download the zip.  
Run `./gradlew build`
