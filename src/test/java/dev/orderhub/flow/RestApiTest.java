package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.rest.RestApi;
import dev.orderhub.rest.RestServer;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Lesende REST-Schnittstelle: Anmeldung, Begrenzung, Routen, und ein echter HTTP-Aufruf. */
class RestApiTest extends FlowTestBase {

  private static final String TOKEN = "0123456789abcdef-secret";
  private static final String AUTH = "Bearer " + TOKEN;

  private JdbcStorage store;
  private Services withInsights;
  private final AtomicLong clock = new AtomicLong(1_000_000);

  @BeforeEach
  void setUp() throws Exception {
    store =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("rest.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    DiscordService discord =
        new DiscordService(
            store,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    InsightsService insights = new InsightsService(store, discord, () -> 60, () -> 3);
    Services base = services;
    withInsights =
        new Services(
            base.logger(),
            store,
            base.scheduler(),
            base.messages(),
            base.settings(),
            base.rates(),
            base.orders(),
            base.delivery(),
            base.claims(),
            base.returns(),
            base.payouts(),
            base.codec(),
            base.matcher(),
            base.menus(),
            insights,
            base.discord());
    for (long price : new long[] {300, 700, 500}) {
      order("DIAMOND", price);
    }
    order("EMERALD", 9_000);
  }

  @AfterEach
  void close() {
    store.close();
  }

  private void order(String material, long price) throws Exception {
    var snapshot = codec.encode(new ItemStack(Material.valueOf(material)));
    long now = System.currentTimeMillis();
    store
        .transact(uow ->
            uow.orders()
                .insert(
                    new NewOrder(
                        UUID.randomUUID(),
                        "srv",
                        snapshot,
                        price,
                        5,
                        1,
                        now + 3_600_000L,
                        false,
                        "gems"),
                    now))
        .join();
  }

  private RestApi api(int perMinute) {
    return new RestApi(withInsights, TOKEN, perMinute, clock::get);
  }

  @Test
  void aShortTokenIsRefusedAtConstruction() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new RestApi(withInsights, "short", 10, clock::get))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withoutAValidTokenEveryRouteIsUnauthorized() {
    RestApi api = api(100);
    assertThat(api.handle("GET", "/v1/health", null, null, "c").status()).isEqualTo(401);
    assertThat(api.handle("GET", "/v1/health", null, "Bearer wrong-wrong-wrong-1", "c").status())
        .isEqualTo(401);
    assertThat(api.handle("GET", "/v1/health", null, "Basic " + TOKEN, "c").status())
        .isEqualTo(401);
    assertThat(api.handle("GET", "/v1/market/orders", null, "", "c").status()).isEqualTo(401);
  }

  @Test
  void tooManyFailedAttemptsAreBlockedEvenForTheRightTokenLater() {
    RestApi api = api(1_000);
    for (int i = 0; i < 10; i++) {
      assertThat(
              api.handle("GET", "/v1/health", null, "Bearer nope-nope-nope-nope", "evil").status())
          .isEqualTo(401);
    }
    assertThat(api.handle("GET", "/v1/health", null, "Bearer nope-nope-nope-nope", "evil").status())
        .isEqualTo(429);
    assertThat(api.handle("GET", "/v1/health", null, AUTH, "good").status()).isEqualTo(200);
    clock.addAndGet(61_000);
    assertThat(api.handle("GET", "/v1/health", null, "Bearer nope-nope-nope-nope", "evil").status())
        .isEqualTo(401);
  }

  @Test
  void theRequestLimitAppliesPerClientAndPerWindow() {
    RestApi api = api(3);
    for (int i = 0; i < 3; i++) {
      assertThat(api.handle("GET", "/v1/health", null, AUTH, "a").status()).isEqualTo(200);
    }
    assertThat(api.handle("GET", "/v1/health", null, AUTH, "a").status()).isEqualTo(429);
    assertThat(api.handle("GET", "/v1/health", null, AUTH, "b").status()).isEqualTo(200);
    clock.addAndGet(60_000);
    assertThat(api.handle("GET", "/v1/health", null, AUTH, "a").status()).isEqualTo(200);
  }

  @Test
  void onlyGetIsAllowedAndUnknownPathsAre404() {
    RestApi api = api(100);
    assertThat(api.handle("POST", "/v1/health", null, AUTH, "c").status()).isEqualTo(405);
    assertThat(api.handle("DELETE", "/v1/market/orders", null, AUTH, "c").status()).isEqualTo(405);
    assertThat(api.handle("GET", "/v1/nothing", null, AUTH, "c").status()).isEqualTo(404);
    assertThat(api.handle("GET", "/", null, AUTH, "c").status()).isEqualTo(404);
  }

  @Test
  void ordersAreFilteredByItemAndPriceAndNeverShowTheOwner() {
    RestApi api = api(100);
    var all = api.handle("GET", "/v1/market/orders", "item=diamond&sort=lowest-price", AUTH, "c");
    var ranged =
        api.handle("GET", "/v1/market/orders", "item=minecraft:diamond&min=4&max=6", AUTH, "c");

    assertThat(all.status()).isEqualTo(200);
    assertThat(all.body())
        .contains("\"unit_price\":300", "\"unit_price\":700")
        .doesNotContain("9000");
    assertThat(all.body().indexOf("\"unit_price\":300"))
        .isLessThan(all.body().indexOf("\"unit_price\":500"));
    assertThat(ranged.body()).contains("\"unit_price\":500").doesNotContain("\"unit_price\":300");
    assertThat(all.body()).doesNotContain("owner").doesNotContain("uuid");
  }

  @Test
  void badParametersGive400AndNothingIsThrown() {
    RestApi api = api(100);
    for (String query :
        new String[] {
          "item=../../etc/passwd",
          "item=a b",
          "limit=abc",
          "sort=nonsense",
          "min=abc",
          "min=10&max=1"
        }) {
      assertThat(api.handle("GET", "/v1/market/orders", query, AUTH, "c").status())
          .as(query)
          .isEqualTo(400);
    }
    assertThat(api.handle("GET", "/v1/market/price", null, AUTH, "c").status()).isEqualTo(400);
    assertThat(api.handle("GET", "/v1/top", "stat=hacks", AUTH, "c").status()).isEqualTo(400);
    assertThat(api.handle("GET", "/v1/market/orders", "%zz=1&limit=5", AUTH, "c").status())
        .isEqualTo(200);
  }

  @Test
  void thePriceRouteShowsDemandAndThePageLimitIsCapped() {
    RestApi api = api(100);
    var price = api.handle("GET", "/v1/market/price", "item=diamond", AUTH, "c");
    assertThat(price.status()).isEqualTo(200);
    assertThat(price.body()).contains("\"lowest\":300", "\"highest\":700", "\"trades\":null");
    var limited = api.handle("GET", "/v1/market/orders", "limit=1", AUTH, "c");
    assertThat(limited.body().split("\"id\":").length).isEqualTo(2);
    var huge = api.handle("GET", "/v1/market/orders", "limit=999999", AUTH, "c");
    assertThat(huge.status()).isEqualTo(200);
  }

  @Test
  void aRealHttpRequestWorksAndRejectsAWrongToken() throws Exception {
    try (RestServer server = RestServer.start("127.0.0.1", 0, api(100), Logger.getLogger("test"))) {
      HttpClient http = HttpClient.newHttpClient();
      URI uri = URI.create("http://127.0.0.1:" + server.port() + "/v1/market/orders?item=diamond");

      var ok =
          http.send(
              HttpRequest.newBuilder(uri).header("Authorization", AUTH).build(),
              HttpResponse.BodyHandlers.ofString());
      var denied =
          http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      var post =
          http.send(
              HttpRequest.newBuilder(uri)
                  .header("Authorization", AUTH)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(ok.statusCode()).isEqualTo(200);
      assertThat(ok.headers().firstValue("Content-Type"))
          .hasValue("application/json; charset=utf-8");
      assertThat(ok.headers().firstValue("Cache-Control")).hasValue("no-store");
      assertThat(ok.body()).contains("\"orders\":[");
      assertThat(denied.statusCode()).isEqualTo(401);
      assertThat(post.statusCode()).isEqualTo(405);
    }
  }
}
