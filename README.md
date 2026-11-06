# PartySystem

> **Læs dette først.**
>
> - Dette projekt er **ikke** mit. Det er en fork af [github.com/BlockCraft-HQ/PartySystem](https://github.com/BlockCraft-HQ/PartySystem).
> - Originalen har ingen licens. Det betyder, at alle rettigheder tilhører ophavsmanden. Intet i dette repo er frigivet under en licens.
> - Kildekoden her er dekompileret fra den udgivne `.jar`. Den er ikke den originale kildekode og er ikke 1:1 med den.
> - Der kommer ingen support, ingen opdateringer og ingen rettelser. Opret ikke issues om fejl, og forvent ikke svar.
> - Jeg anbefaler ikke, at du bruger pluginet. Gør du det, er det på eget ansvar.
> - Pluginet leveres som det er, uden nogen form for garanti.

## Hvad er det?

Et Paper-plugin til Minecraft-servere, hvor spillere bygger deres eget kit eller vælger et forudlavet kit fra `Kits`-mappen og kan restocke det igen og igen.

## Krav

- PlaceholderAPI (valgfri)

## Kommandoer

| Kommando | Alias |
| --- | --- |
| `/party` | `/pt` |

Brug `/party help` for at se, hvad der er tilgængeligt.

## Rettigheder

| Rettighed | Standard | Beskrivelse |
| --- | --- | --- |
| `partysystem.use` | alle | Brug af spiller-kommandoer |
| `partysystem.admin` | op | Brug af admin-kommandoer |
| `partysystem.restock.nocooldown` | op | Ingen cooldown på restock |
| `partysystem.command.bypass` | op | Må bruge alle kommandoer i party |

## Byg selv

Kræver JDK 21 og Maven.

```
mvn package
```

Jar'en ligger bagefter i `target/PartySystem.jar`.

## Licens og ophavsret

Der er ingen licens på dette projekt. Alt i dette repo tilhører den oprindelige ophavsmand. Brug, kopiering og videredistribution sker udelukkende på eget ansvar.