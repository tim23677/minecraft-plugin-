# FAQ

Can money or items be lost in a crash? Every movement is journaled first. After a restart OrderHub completes the safe cases and moves unclear ones to MANUAL_REVIEW. It never repeats a payment of unclear outcome automatically.

Does it work with my economy plugin? Through Vault. Tested: EssentialsX with VaultUnlocked (1.1.0 scenario). Others are untested, see COMPATIBILITY.md.

Does it work on Folia? It is marked Folia-supported and all tasks go through region, entity, global or async schedulers. A play-through on Folia with an economy was not done.

Can Bedrock players use it? The menus are chest inventories and the chat input is used for numbers. See the Bedrock notes. A test with Geyser was not run.

Why no price recommendation? A minimum number of completed trades is needed (`market.min-trades-for-recommendation`).
