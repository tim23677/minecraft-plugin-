# Installation

Requirements: Paper or Folia 1.21.x, Java 21, Vault and an economy plugin that registers with Vault.

1. Stop the server.
2. Copy `OrderHub-<version>.jar` into `plugins`. Servers without internet access use `OrderHub-<version>-bundled.jar` instead (SQLite only).
3. Install Vault and an economy plugin if they are missing.
4. Start the server. On the first start Paper downloads the database libraries from Maven Central into its `libraries` folder.
5. Edit `plugins/OrderHub/config.yml` (language, prices, fees, limits) and run `/orderadmin reload`.

SQLite is the default and needs no setup. For several servers see "Multi-server setup".
