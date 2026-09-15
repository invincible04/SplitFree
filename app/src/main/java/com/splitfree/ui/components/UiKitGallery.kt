package com.splitfree.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SplitFreeColors
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.AdaptiveSizeTokens
import com.splitfree.ui.util.adaptiveSizeTokens

private val SampleKeys = listOf("you", "member-1", "member-2", "member-3", "member-4", "member-5")
private val SampleNames =
    mapOf(
        "you" to "You",
        "member-1" to "Member 2",
        "member-2" to "Member 3",
        "member-3" to "Member 4",
        "member-4" to "Member 5",
        "member-5" to "Member 6"
    )
private val SampleCategories =
    listOf("food", "transport", "shopping", "entertainment", "utilities", "rent", "health", "other", "")

/**
 * Every kit component with fixture data, laid out top to bottom. It lives in `main` only so the Robolectric
 * render test (`UiKitRenderTest`) and the debug `@Preview`s share one source of truth; production code never
 * calls it (R8 drops it from release). Text here is fixture copy, not user-facing strings.
 */
@Composable
internal fun UiKitGallery(modifier: Modifier = Modifier) {
    val tokens = adaptiveSizeTokens()
    val palette = MaterialTheme.splitFree
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var choice by rememberSaveable { mutableIntStateOf(0) }
    var currency by rememberSaveable { mutableStateOf("INR") }
    var disclosed by rememberSaveable { mutableStateOf(true) }

    // A Surface (not a bare background) so LocalContentColor is provided exactly as MainActivity does.
    Surface(
        modifier = modifier.fillMaxSize().testTag("ui_kit_gallery"),
        color = MaterialTheme.colorScheme.background
    ) {
        GalleryBody(
            tokens = tokens,
            palette = palette,
            segment = segment,
            onSegment = { segment = it },
            choice = choice,
            onChoice = { choice = it },
            currency = currency,
            onCurrency = { currency = it },
            disclosed = disclosed,
            onDisclose = { disclosed = !disclosed }
        )
    }
}

@Composable
private fun GalleryBody(
    tokens: AdaptiveSizeTokens,
    palette: SplitFreeColors,
    segment: Int,
    onSegment: (Int) -> Unit,
    choice: Int,
    onChoice: (Int) -> Unit,
    currency: String,
    onCurrency: (String) -> Unit,
    disclosed: Boolean,
    onDisclose: () -> Unit
) {
    Column {
        SfTopBar(
            title = "Goa trip",
            onBack = {},
            actions = {
                SfTextButton(text = "Invite", onClick = {})
                SfIconButton(icon = Icons.Outlined.MoreHoriz, contentDescription = "Group tools", onClick = {})
                IdentityTile(initial = null, contentDescription = "Settings", onClick = {})
                Spacer(Modifier.width(tokens.screenPaddingHorizontal - 4.dp))
            }
        )

        Column(Modifier.padding(horizontal = tokens.screenPaddingHorizontal)) {
            SfLargeTitleHeader(eyebrow = "A little less keeping score", title = "Your groups.") {
                StatusPill(text = "Demo data", tone = PillTone.Online)
            }

            // Currency chooser line
            CurrencyLine(currencies = listOf("INR", "USD"), selected = currency, onSelect = onCurrency)
            Spacer(Modifier.height(12.dp))

            // Balance hero
            HeroCardSample()

            SectionHead(title = "Your shared spaces") { Meta("3 groups") }
            SfCard(onClick = {}) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryIcon(category = "travel", size = 47.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Goa trip",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Meta("4 people · INR")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        SignedMoneyText(
                            amountMinor = 240000,
                            currency = "INR",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Meta("owed to you")
                    }
                }
            }

            SectionHead(title = "Buttons") { Meta("all states") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SfPrimaryButton(text = "Save expense", onClick = {})
                SfPrimaryButton(text = "Saving", onClick = {}, loading = true)
                SfPrimaryButton(text = "Disabled", onClick = {}, enabled = false)
                SfPrimaryButton(
                    text = "Replace identity",
                    onClick = {},
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
                SfAccentButton(text = "Create group", onClick = {}, leadingIcon = Icons.Outlined.Add)
                SfAccentButton(text = "Disabled", onClick = {}, enabled = false)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    SfSecondaryButton(text = "Cancel", onClick = {}, modifier = Modifier.weight(1f))
                    SfSecondaryButton(text = "Off", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SfTextButton(text = "See all", onClick = {})
                    SfTextButton(
                        text = "Restore an existing identity",
                        onClick = {},
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    SfIconButton(icon = Icons.Outlined.MoreHoriz, contentDescription = "More", onClick = {})
                }
            }

            SectionHead(title = "People") { Meta("avatars, stack, pills, tile") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemberAvatar(pubkey = "you", name = "You")
                MemberAvatar(pubkey = "member-1", name = "Member 2", size = 40.dp)
                MemberAvatar(pubkey = "🏖-key", name = "🏖 Beach", size = 40.dp)
                MemberStack(pubkeys = SampleKeys, names = SampleNames)
                Meta("6 people")
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(text = "Neutral")
                StatusPill(text = "Online", tone = PillTone.Online)
                StatusPill(text = "Offline", tone = PillTone.Offline)
                StatusDot(connected = true, contentDescription = "Connected")
                StatusDot(connected = false, contentDescription = "Disconnected")
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IdentityTile(initial = "P", contentDescription = null, size = 55.dp)
                IdentityTile(initial = null, contentDescription = "Person")
                Meta("identity tile · 55 / 48")
            }

            SectionHead(title = "Brand") { Meta("launcher mark · 96 / 48") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(modifier = Modifier.testTag("ui_kit_brand_mark"))
                BrandMark(size = 48.dp)
                Meta("home-screen icon tile")
            }

            SectionHead(title = "Notices")
            HintCard(
                text = "Expenses are encrypted on the device before sharing. No account needed.",
                icon = Icons.Outlined.Shield
            )
            Spacer(Modifier.height(10.dp))
            WarningCard(
                text =
                "Anyone with a real invite may be able to join and read group history. " +
                    "Share it only with people you trust."
            )
            Spacer(Modifier.height(10.dp))
            WarningCard(
                text = "Offline: changes are saved on this phone and sync when you reconnect.",
                icon = Icons.Outlined.WifiOff
            )

            SectionHead(title = "Segmented tabs")
            SegmentedTabs(
                options = listOf("Summary", "Expenses", "People"),
                selectedIndex = segment,
                onSelect = onSegment
            )

            SectionHead(title = "Rows") { Meta("choice · settings · expandable · detail") }
            SfListCard {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    ChoiceRow(
                        title = "Equally",
                        subtitle = "Everyone pays the same",
                        selected = choice == 0,
                        onClick = { onChoice(0) }
                    )
                    SfDivider()
                    ChoiceRow(
                        title = "Exact amounts",
                        subtitle = null,
                        selected = choice == 1,
                        onClick = { onChoice(1) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SfListCard {
                SettingsRow(icon = Icons.Outlined.WbSunny, title = "Appearance", subtitle = "Light theme", onClick = {})
                SfDivider()
                SettingsRow(
                    icon = Icons.Outlined.Key,
                    title = "Backup & recovery",
                    subtitle = "Identity phrase + encrypted group export",
                    onClick = {}
                )
                SfDivider()
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    title = "Hide sender metadata",
                    subtitle = "Gift Wrap privacy",
                    onClick = {},
                    trailing = { Switch(checked = true, onCheckedChange = null) }
                )
                SfDivider()
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    title = "Replace identity",
                    subtitle = "Advanced security action",
                    onClick = {},
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
            SfExpandableRow(
                icon = Icons.Outlined.Add,
                title = "Add relay",
                subtitle = "7 suggested relays",
                expanded = disclosed,
                onToggle = onDisclose,
                modifier = Modifier.testTag("ui_kit_expandable"),
                framed = true
            ) {
                Text(
                    "Disclosed content sits below the second hairline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            SfListCard {
                DetailRow(label = "Pending events", value = "3")
                SfDivider()
                DetailRow(label = "Member 3") {
                    MoneyText(amountMinor = 40000, currency = "INR", style = MaterialTheme.typography.titleSmall)
                }
            }

            SectionHead(title = "Money") { Meta("tabular · signed") }
            SfListCard {
                OwedRow(pubkey = "member-2", name = "Member 3 owes you", amount = 80000)
                SfDivider()
                OwedRow(pubkey = "member-3", name = "You owe Member 4", amount = -160000)
                SfDivider()
                OwedRow(pubkey = "member-1", name = "Settled with Member 2", amount = 0)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MoneyText(amountMinor = 111111, currency = "INR", style = MaterialTheme.typography.displaySmall)
                MoneyText(amountMinor = 999999, currency = "INR", style = MaterialTheme.typography.displaySmall)
            }

            SectionHead(title = "Sheet") { Meta("header · footer") }
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column {
                    SfSheetHandle()
                    SfSheetHeader(title = "Choose how to split", onClose = {})
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            "Choose who is included. Remainders are distributed fairly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SfSheetFooter(
                            secondary = {
                                SfSecondaryButton(text = "Cancel", onClick = {}, modifier = Modifier.fillMaxWidth())
                            },
                            primary = { SfPrimaryButton(text = "Apply split", onClick = {}) }
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            SectionHead(title = "Empty state")
            SfCard {
                EmptyState(
                    icon = Icons.Outlined.Group,
                    title = "No groups yet",
                    body = "Create a group for a trip, a flat or a night out, then invite people."
                ) {
                    SfSecondaryButton(text = "New group", onClick = {}, leadingIcon = Icons.Outlined.Add)
                }
            }

            SectionHead(title = "Category icons") { Meta("${SampleCategories.size} keys") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SampleCategories.take(5).forEach { CategoryIcon(category = it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SampleCategories.drop(5).forEach { CategoryIcon(category = it) }
            }
            Spacer(Modifier.height(24.dp))
        }

        SfBottomDock {
            SfPrimaryButton(text = "Save expense", onClick = {})
            Spacer(Modifier.height(8.dp))
            Text(
                "Saves on this phone first. No connection needed.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.faint,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun Meta(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** The inverted hero balance card built from theme roles only. */
@Composable
private fun HeroCardSample() {
    val palette = MaterialTheme.splitFree
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = palette.hero,
        contentColor = palette.onHero
    ) {
        Box {
            SfHeroBackdrop()
            Column(Modifier.padding(24.dp)) {
                MiniLabel(text = "Net to receive · INR", color = palette.heroMuted)
                Spacer(Modifier.height(12.dp))
                MoneyText(
                    amountMinor = 250000,
                    currency = "INR",
                    style = MaterialTheme.typography.displayLarge,
                    color = palette.onHero
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    HeroStat(label = "You are owed", amountMinor = 315000, modifier = Modifier.weight(1f))
                    HeroStat(label = "You owe", amountMinor = 65000, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, amountMinor: Long, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.splitFree
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.heroMuted)
        Spacer(Modifier.height(4.dp))
        MoneyText(
            amountMinor = amountMinor,
            currency = "INR",
            style = MaterialTheme.typography.titleMedium,
            color = palette.onHero
        )
    }
}

@Composable
private fun OwedRow(pubkey: String, name: String, amount: Long) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(pubkey = pubkey, name = name, size = 36.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            SignedMoneyText(amountMinor = amount, currency = "INR", style = MaterialTheme.typography.titleSmall)
        }
        // Cap trailing text buttons so the label wraps instead of starving the weighted column at large text.
        SfTextButton(text = "Record payment", onClick = {}, modifier = Modifier.widthIn(max = 132.dp))
    }
}
