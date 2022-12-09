/*
 * CarbonChat
 *
 * Copyright (c) 2021 Josua Parks (Vicarious)
 *                    Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.draycia.carbon.paper.util;

import com.loohp.interactivechat.modules.PlayernameDisplay;
import java.util.Random;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.events.CarbonNicknameEvent;

public class ICHook {
    public ICHook() throws NoSuchFieldException {
        final var flagField = PlayernameDisplay.class.getDeclaredField("flag");
        final var namesField = PlayernameDisplay.class.getDeclaredField("names");
        final var randomField = PlayernameDisplay.class.getDeclaredField("random");

        flagField.setAccessible(true);
        namesField.setAccessible(true);
        randomField.setAccessible(true);

        CarbonChatProvider.carbonChat().eventHandler().subscribe(CarbonNicknameEvent.class, event -> {
            flagField.setInt(null, ((Random) randomField.get(null)).nextInt());
            namesField.set(null, null);
        });
    }
}
