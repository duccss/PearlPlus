## PearlPlus

PearlPlus lets you allow players at your base to load pearls through chat whispers without granting any other permissions. You can configure which pearls players can load using commands through discord or console. The config is saved to `plugins/config/pearl-plus.json`

Download the [lastest build](https://github.com/duccss/PearlPlus/releases/latest) and place the jar file in your proxys plugin folder.

### Management Commands

#### You can use either `pp` or `pearl+`

```bash
pearl+ <on/off>
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

Setup regular pearlloader positions using the built in zenithproxy module. E.g `pearlLoader add <id> <x> <y> <z>`. Add a player to pearl+'s config using `pearl+ allow <username> <id>`. That player can now whisper "load" to the zenith bot and the bot will load the pearl. Players can add the pearl id after "load" to have a specific pearl loaded if they have more than one.
```bash
/whisper <botName> load <optionalID> 
```

### Building The Plugin

Clone the repo or download the zip.  
Run `./gradlew build`
