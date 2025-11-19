## PearlPlus

PearlPlus automatically detects new stasis pearls and registers them with zeniths built in pearl loader. Pearl throwers are then allowed to load these pearls through chat whispers.
The config is saved to `plugins/config/pearlplus.json`

Download the [lastest build](https://github.com/duccss/PearlPlus/releases/latest) and place the jar file in your proxys plugin folder.

### Management Commands

#### You can use either `pp` or `pearlplus`

```bash
pearlplus <on/off>
```
```bash
pearlplus allow/deny playerName pearlID
```
```bash
pearlplus list
```
```bash
pearlplus strict on/off
```
```bash
pearlplus autodetect on/off
```
```bash
pearlplus autodetect temp on/off
```

### Usage

Simply throw a new ender pearl and once it becomes stable the bot will register a pearl loader entry with zenith, setting the pearlID as the throwers name with an incrementing number. That player can now whisper `load` to the zenith bot and the bot will load the pearl. Players with multiple pearls can add the pearlID after `load` to have a specific pearl loaded. Players will recieve the pearlID from a whisper when a new pearl is registered.
```bash
/w <botName> load <optionalID> 
```
Temp mode automatically removes pearl positions where a pearl isnt detected. May be buggy.

Can be enabled with `pearlplus autodetect temp on` 

#### Manual setup
Setup regular pearlloader positions using the built in zenithproxy module. E.g `pearlLoader add <id> <x> <y> <z>`. Add a player to pearlplus's config using `pearlplus allow <username> <id>`.

#### 2b2t / Anti-spam

By default the bot checks the nearest player to a new pearl and assumes that player threw it. This might be buggy but might be necessary as it seems Hause has disabled resolving entity owners. Resolving the name is bulletproof but server owners can 'disable' it.

If you aren't playing 2b2t its highly recommended to resolve names, this can be done with `pearlplus 2b2t off`.

By default you can add a random word after `load` or the `pearlID` to get around anti-spam.

This can be disabled using `pearlplus strict on`.

### Building The Plugin

Clone the repo or download the zip.
Run `chmod +x gradlew`
 then `./gradlew build`
