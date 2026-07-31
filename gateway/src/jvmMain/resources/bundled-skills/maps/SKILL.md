---
name: maps
description: "Geocode, POIs, routes, timezones via OpenStreetMap/OSRM."
source: BUNDLED
requires_cli: python3
platforms: jvm
---

# Maps Skill

Location intelligence using free, open data sources. 8 commands, 44 POI categories, zero dependencies (Python stdlib only), no API key required.

Data sources: OpenStreetMap/Nominatim, Overpass API, OSRM, TimeAPI.io.

## When to Use

- User sends a location pin (latitude/longitude) → `nearby`
- User wants coordinates for a place name → `search`
- User has coordinates and wants the address → `reverse`
- User asks for nearby restaurants, hospitals, pharmacies, hotels, etc. → `nearby`
- User wants driving/walking/cycling distance or travel time → `distance`
- User wants turn-by-turn directions between two places → `directions`
- User wants timezone information for a location → `timezone`
- User wants to search for POIs within a geographic area → `area` + `bbox`

## Prerequisites

Python 3.8+ (stdlib only — no pip installs needed).

Script path: `scripts/maps_client.py` (bundled with this skill).

## Commands

### search — Geocode a place name

```
execute_command(command="python3", args=["scripts/maps_client.py", "search", "Eiffel Tower"])
execute_command(command="python3", args=["scripts/maps_client.py", "search", "1600 Pennsylvania Ave, Washington DC"])
```

Returns: lat, lon, display name, type, bounding box, importance score.

### reverse — Coordinates to address

```
execute_command(command="python3", args=["scripts/maps_client.py", "reverse", "48.8584", "2.2945"])
```

Returns: full address breakdown (street, city, state, country, postcode).

### nearby — Find places by category

```
# By coordinates
execute_command(command="python3", args=["scripts/maps_client.py", "nearby", "48.8584", "2.2945", "restaurant", "--limit", "10"])
execute_command(command="python3", args=["scripts/maps_client.py", "nearby", "40.7128", "-74.0060", "hospital", "--radius", "2000"])

# By address — --near auto-geocodes
execute_command(command="python3", args=["scripts/maps_client.py", "nearby", "--near", "Times Square, New York", "--category", "cafe"])

# Multiple categories
execute_command(command="python3", args=["scripts/maps_client.py", "nearby", "--near", "downtown austin", "--category", "restaurant", "--category", "bar", "--limit", "10"])
```

46 categories: restaurant, cafe, bar, hospital, pharmacy, hotel, guest_house, camp_site, supermarket, atm, gas_station, parking, museum, park, school, university, bank, police, fire_station, library, airport, train_station, bus_stop, church, mosque, synagogue, dentist, doctor, cinema, theatre, gym, swimming_pool, post_office, convenience_store, bakery, bookshop, laundry, car_rental, car_wash, veterinary, zoo, playground, viewpoint, beach, waterfall, fountain.

### distance — Travel distance and time

```
execute_command(command="python3", args=["scripts/maps_client.py", "distance", "--from", "Paris", "--to", "Lyon", "--mode", "driving"])
```

### directions — Turn-by-turn navigation

```
execute_command(command="python3", args=["scripts/maps_client.py", "directions", "--from", "Paris", "--to", "Lyon"])
```

### timezone — Get timezone for a location

```
execute_command(command="python3", args=["scripts/maps_client.py", "timezone", "48.8584", "2.2945"])
```
