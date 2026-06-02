# Examples

Illustrative, dependency-free samples. They are compiled by the `examples` Gradle source set (so they
cannot drift from the library API) but are not part of the published jar.

## `ExampleNetGameStatsSource`

A minimal [`GameStatsSource`](../src/main/java/lol/trq/alts/spi/GameStatsSource.java) for a fictional
`example.net` server. It shows the whole shape of the feature: a source reports a `serverId` and returns
ready-to-render `GameStats.Stat` chips; the library only caches them.

Wire it and read it back:

```java
AltsRuntime<MyHandle> alts = new AltsRuntime.Builder<MyHandle>()
        .sessionInjector(...)
        .vaultDirectory(...)
        .mainThread(...)
        .toastSink(...)
        .gameStatsSource(new ExampleNetGameStatsSource())   // one per server; call again for more
        .build();

// uuid -> stats for example.net (null on the first call while the background fetch runs,
// then the chips on a later frame; unknown server ids return null forever).
GameStats stats = alts.gameStats("example.net").get(playerUuid);
if (stats != null) {
    for (GameStats.Stat chip : stats.stats()) {
        render(chip.label(), chip.value());   // e.g. "rank Veteran", "wins 128"
    }
}
```

For a static, in-memory source (handy for tests and demos), use the shipped
[`StaticGameStatsSource`](../src/main/java/lol/trq/alts/spi/StaticGameStatsSource.java):

```java
var source = new StaticGameStatsSource(
        "example.net",
        Map.of(playerUuid, List.of(new GameStats.Stat("rank", "Veteran"))));
```
