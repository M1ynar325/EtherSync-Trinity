package com.etherstories.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCommandTest {

    private ServerMock server;
    private ESLinkPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ESLinkPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playerWithoutAdminPermissionCannotUseMarketCommand() {
        PlayerMock player = server.addPlayer("Steve");
        player.setOp(false);

        new LinkCommand(plugin).onCommand(player, null, "link", new String[]{"market"});

        String all = drain(player);
        assertTrue(all.contains("没有权限"));
    }

    @Test
    void adminCanAddAndSetDefaultExchange() {
        PlayerMock admin = server.addPlayer("Alex");
        admin.setOp(true);
        LinkCommand cmd = new LinkCommand(plugin);

        cmd.onCommand(admin, null, "link", new String[]{"market"});
        String listOutput = drain(admin);
        assertTrue(listOutput.contains("未登记独立交易所"));

        cmd.onCommand(admin, null, "link",
                new String[]{"market", "add", "ether", "http://127.0.0.1:8765", "token", "以太货栈"});
        String addOutput = drain(admin);
        assertTrue(addOutput.contains("已登记交易所"));

        cmd.onCommand(admin, null, "link", new String[]{"market", "default", "ether"});
        String defaultOutput = drain(admin);
        assertTrue(defaultOutput.contains("服务器默认交易所已切换为"));

        assertTrue("ether".equals(plugin.getConfig().getString("markets.default")));
    }

    private String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }
}
