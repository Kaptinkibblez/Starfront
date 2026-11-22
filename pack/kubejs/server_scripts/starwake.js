// Starwake modpack: starting kit and pack-wide recipe tweaks.

// Every player gets the quest book (and a few basics) the first time they join.
PlayerEvents.loggedIn(event => {
  const player = event.player
  if (player.stages.has('starwake_started')) return
  player.stages.add('starwake_started')
  player.give('heracles:quest_book')
  player.give(Item.of('minecraft:bread', 8))
  player.tell(Text.gold('Welcome to Starwake! Press U (or use the Quest Book) to open your quests.'))
})

ServerEvents.recipes(event => {
  // A lost quest book can be crafted again.
  event.shapeless('heracles:quest_book', ['minecraft:book', 'create:cogwheel'])
})
