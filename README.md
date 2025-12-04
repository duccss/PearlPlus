## PearlPlus

PearlPlus automatically detects new stasis pearls and registers them with its own pearl loader. Pearl throwers can then load these pearls through chat whispers.
The config is saved to `plugins/config/pearlplus.json`

In Zenith run `plugins download https://github.com/duccss/PearlPlus/releases/download/2.0.0/PearlPlus-2.0.0.jar` or download the [lastest build](https://github.com/duccss/PearlPlus/releases/latest) and place the jar file in your proxys plugin folder.

### Management Commands

#### You can use either `pp` or `pearlplus`

```bash
pearlplus <on/off>
```
```bash
pearlplus add <playerName> <pearlId> <x> <y> <z>
```
```bash
pearlplus del <playerName> <pearlId>
```
```bash
pearlplus list
```
```bash
pearlplus defaultpearlid <word|none>
```
```bash
pearlplus autodefault <on/off>
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
```bash
pearlplus returnpos <on/off>
```
```bash
pearlplus distancecheck <on/off>
```

### Ingame Whisper Commands

There are a few ingame commands players can whisper to the bot to manage their pearls.

`pearls` will list all pearlID's with an asterisk next to ID's where a pearl isnt detected.

`rename oldPearlID newPearlID` changes the pearlID.

`default PearlID` sets that pearl as default when whispering load with `autodefault` disabled.

### Usage

Simply throw a new ender pearl and once it becomes stable the bot will register it, setting the pearlID as "Base" by default with an incrementing number for subsequent pearls. That player can now whisper `load` to the zenith bot and the bot will load the pearl. Players with multiple pearls can add the pearlID after `load` to have a specific pearl loaded. Players will recieve a warning whisper when loading a stasis chamber where a pearl isnt detected.
```bash
/w <botName> load <optionalID> 
```
By default, when a player doesnt specify which pearl they want loaded the bot will load whatever one where a pearl is detected. Can be disabled with `pp autodefault off`

Temp mode automatically removes pearl positions where a pearl isnt detected. May be buggy.

Can be enabled with `pearlplus autodetect temp on` 

#### Manual setup
Use the `pp add/del` commands to setup manually.

#### 2b2t / Anti-spam

By default the bot resolves the username of pearl throwers with entity ID's. Some servers might not allow this so if the bot is unable to register pearls automatically use `pp distancecheck on`. This will get the throwers name from the closest player to it.

By default you can add a random word after `load` or the `pearlID` to get around anti-spam. This can be disabled using `pearlplus strict on`.

### Building The Plugin

Clone the repo or download the zip.
Run `chmod +x gradlew`
 then `./gradlew build`
