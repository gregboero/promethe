---
name: ascii-art
description: "ASCII art: pyfiglet, cowsay, boxes, image-to-ascii."
source: BUNDLED
platforms: jvm
---

# ASCII Art Skill

Multiple tools for different ASCII art needs. All tools are local CLI programs or free REST APIs — no API keys required.

## Tool 1: Text Banners (pyfiglet — local)

Render text as large ASCII art banners. 571 built-in fonts.

### Setup

```
execute_command(command="pip", args=["install", "pyfiglet", "--break-system-packages", "-q"])
```

### Usage

```
execute_command(command="python3", args=["-m", "pyfiglet", "YOUR TEXT", "-f", "slant"])
execute_command(command="python3", args=["-m", "pyfiglet", "TEXT", "-f", "doom", "-w", "80"])
execute_command(command="python3", args=["-m", "pyfiglet", "--list_fonts"])
```

### Recommended fonts

| Style | Font | Best for |
|-------|------|----------|
| Clean & modern | `slant` | Project names, headers |
| Bold & blocky | `doom` | Titles, logos |
| Big & readable | `big` | Banners |
| Classic banner | `banner3` | Wide displays |
| Compact | `small` | Subtitles |
| Cyberpunk | `cyberlarge` | Tech themes |
| 3D effect | `3-d` | Splash screens |
| Gothic | `gothic` | Dramatic text |

### Tips

- Preview 2-3 fonts and let the user pick their favorite
- Short text (1-8 chars) works best with detailed fonts like `doom` or `block`
- Long text works better with compact fonts like `small` or `mini`

## Tool 2: Text Banners (asciified API — remote, no install)

Free REST API that converts text to ASCII art. 250+ FIGlet fonts. Returns plain text directly. Use when pyfiglet is not installed or as a quick alternative.

### Usage

```
http_fetch(url="https://asciified.thelicato.io/api/v2/ascii?text=Hello+World", method="GET")

# With a specific font
http_fetch(url="https://asciified.thelicato.io/api/v2/ascii?text=Hello&font=Slant", method="GET")
http_fetch(url="https://asciified.thelicato.io/api/v2/ascii?text=Hello&font=Doom", method="GET")
http_fetch(url="https://asciified.thelicato.io/api/v2/ascii?text=Hello&font=Star+Wars", method="GET")

# List all available fonts (returns JSON array)
http_fetch(url="https://asciified.thelicato.io/api/v2/fonts", method="GET")
```

### Tips

- URL-encode spaces as `+` in the text parameter
- The response is plain text ASCII art — no JSON wrapping, ready to display
- Font names are case-sensitive; use the fonts endpoint to get exact names

## Tool 3: Cowsay (Message Art)

Classic tool that wraps text in a speech bubble with an ASCII character.

### Setup

```
execute_command(command="apt", args=["install", "-y", "cowsay"])
# or on macOS:
execute_command(command="brew", args=["install", "cowsay"])
```

### Usage

```
execute_command(command="cowsay", args=["Hello World"])
execute_command(command="cowsay", args=["-f", "tux", "Linux rules"])
execute_command(command="cowsay", args=["-f", "dragon", "Rawr!"])
execute_command(command="cowthink", args=["Hmm..."])
execute_command(command="cowsay", args=["-l"])
```

### Available characters (50+)

`beavis.zen`, `bong`, `bunny`, `cheese`, `daemon`, `default`, `dragon`,
`dragon-and-cow`, `elephant`, `eyes`, `flaming-skull`, `ghostbusters`,
`hellokitty`, `kiss`, `kitty`, `koala`, `luke-koala`, `mech-and-cow`,
`meow`, `moofasa`, `moose`, `ren`, `sheep`, `skeleton`, `small`,
`stegosaurus`, `stimpy`, `supermilker`, `surgery`, `three-eyes`,
`turkey`, `turtle`, `tux`, `udder`, `vader`, `vader-koala`, `www`

### Eye/tongue modifiers

```
execute_command(command="cowsay", args=["-b", "Borg"])       # =_= eyes
execute_command(command="cowsay", args=["-d", "Dead"])       # x_x eyes
execute_command(command="cowsay", args=["-g", "Greedy"])     # $_$ eyes
execute_command(command="cowsay", args=["-p", "Paranoid"])   # @_@ eyes
execute_command(command="cowsay", args=["-s", "Stoned"])     # *_* eyes
execute_command(command="cowsay", args=["-w", "Wired"])      # O_O eyes
execute_command(command="cowsay", args=["-e", "OO", "Msg"])  # Custom eyes
execute_command(command="cowsay", args=["-T", "U ", "Msg"])  # Custom tongue
```

## Tool 4: Boxes (Decorative Borders)

Draw decorative ASCII art borders/frames around any text. 70+ built-in designs.

### Setup

```
execute_command(command="apt", args=["install", "-y", "boxes"])
# or on macOS:
execute_command(command="brew", args=["install", "boxes"])
```

### Usage

Pipe text through boxes using shell:
```
execute_command(command="sh", args=["-c", "echo 'Hello World' | boxes"])
execute_command(command="sh", args=["-c", "echo 'Hello World' | boxes -d stone"])
execute_command(command="sh", args=["-c", "echo 'Hello World' | boxes -d parchment"])
execute_command(command="sh", args=["-c", "echo 'Hello World' | boxes -d cat"])
execute_command(command="sh", args=["-c", "boxes -l"])
```

### Popular designs

`stone`, `parchment`, `cat`, `dog`, `mouse`, `unicornsay`, `santa`,
`spring`, `stark1`, `peek`, `boy`, `girl`, `capgirl`, `diamonds`
