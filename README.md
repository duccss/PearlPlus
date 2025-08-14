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
```bash
pearl+ strict <on/off>
```

### Usage

Setup regular pearlloader positions using the built in zenithproxy module. E.g `pearlLoader add <id> <x> <y> <z>`. Add a player to pearl+'s config using `pearl+ allow <username> <id>`. That player can now whisper `load` to the zenith bot and the bot will load the pearl. Players with multiple pearls can add the ID after `load` to have a specific pearl loaded.
```bash
/w <botName> load <optionalID> 
```
#### 2b2t / Anti-spam

On 2b2t or other servers with strict anti-spam plugins you may need to disable strict mode (enabled by default) which then allows you to add a random string after `load` or the `pearlID`.
```bash
/w <botName> load <optionalID> <randomString>
```

### Building The Plugin

Clone the repo or download the zip.  
Run `./gradlew build`
