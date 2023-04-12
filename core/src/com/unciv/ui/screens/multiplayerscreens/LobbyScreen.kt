package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.multiplayer.apiv2.AccountResponse
import com.unciv.logic.multiplayer.apiv2.FriendRequestResponse
import com.unciv.logic.multiplayer.apiv2.FriendResponse
import com.unciv.logic.multiplayer.apiv2.GetLobbyResponse
import com.unciv.logic.multiplayer.apiv2.LobbyResponse
import com.unciv.logic.multiplayer.apiv2.OnlineAccountResponse
import com.unciv.logic.multiplayer.apiv2.StartGameResponse
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.AutoScrollPane
import com.unciv.ui.components.KeyCharAndCode
import com.unciv.ui.components.MultiplayerButton
import com.unciv.ui.components.PencilButton
import com.unciv.ui.components.RefreshButton
import com.unciv.ui.components.SearchButton
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.keyShortcuts
import com.unciv.ui.components.extensions.onActivation
import com.unciv.ui.components.extensions.onClick
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.popups.InfoPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.newgamescreen.GameOptionsTable
import com.unciv.ui.screens.newgamescreen.MapOptionsInterface
import com.unciv.ui.screens.newgamescreen.MapOptionsTable
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Log
import com.unciv.utils.concurrency.Concurrency
import kotlinx.coroutines.runBlocking
import java.util.*

/**
 * Lobby screen for open lobbies
 *
 * On the left side, it provides a list of players and their selected civ.
 * On the right side, it provides a chat bar for multiplayer lobby chats.
 * Between those, there are four menu buttons for a) game settings, b) map settings,
 * c) to invite new players and d) to start the game. It also has a footer section
 * like the [PickerScreen] but smaller, with a leave button on the left and
 * two buttons for the social tab and the in-game help on the right side.
 */
class LobbyScreen(
    private val lobbyUUID: UUID,
    private val lobbyChatUUID: UUID,
    private var lobbyName: String,
    private val maxPlayers: Int,
    private var currentPlayers: List<AccountResponse>,
    private val hasPassword: Boolean,
    private val owner: AccountResponse,
    override val gameSetupInfo: GameSetupInfo
): BaseScreen(), MapOptionsInterface {

    constructor(lobby: LobbyResponse): this(lobby.uuid, lobby.chatRoomUUID, lobby.name, lobby.maxPlayers, mutableListOf(), lobby.hasPassword, lobby.owner, GameSetupInfo.fromSettings())
    constructor(lobby: GetLobbyResponse): this(lobby.uuid, lobby.chatRoomUUID, lobby.name, lobby.maxPlayers, lobby.currentPlayers, lobby.hasPassword, lobby.owner, GameSetupInfo.fromSettings())

    override var ruleset = RulesetCache.getComplexRuleset(gameSetupInfo.gameParameters)

    private val gameOptionsTable: GameOptionsTable
    private val mapOptionsTable = MapOptionsTable(this)

    private val me
        get() = runBlocking { game.onlineMultiplayer.api.account.get() }!!
    private val screenTitle
        get() = "Lobby: [$lobbyName] [${currentPlayers.size + 1}]/[$maxPlayers]".toLabel(fontSize = Constants.headingFontSize)

    private val lobbyPlayerList = LobbyPlayerList(lobbyUUID, mutableListOf(), this) { update() }
    private val chatMessageList = ChatMessageList(lobbyChatUUID, game.onlineMultiplayer)
    private val changeLobbyNameButton = PencilButton()
    private val menuButtonGameOptions = "Game options".toTextButton()
    private val menuButtonMapOptions = "Map options".toTextButton()
    private val menuButtonInvite = "Invite player".toTextButton()
    private val menuButtonStartGame = "Start game".toTextButton()
    private val bottomButtonLeave = if (owner.uuid == me.uuid) "Close lobby".toTextButton() else "Leave".toTextButton()
    private val bottomButtonSocial = MultiplayerButton()
    private val bottomButtonHelp = "Help".toTextButton()

    init {
        gameSetupInfo.gameParameters.isOnlineMultiplayer = true
        gameOptionsTable = GameOptionsTable(this, multiplayerOnly = true, updatePlayerPickerRandomLabel = {}, updatePlayerPickerTable = { x ->
            Log.error("Updating player picker table with '%s' is not implemented yet.", x)
        })

        changeLobbyNameButton.onActivation {
            ToastPopup("Renaming a lobby is not implemented.", stage)
        }

        menuButtonGameOptions.onClick {
            WrapPopup(stage, gameOptionsTable)
        }
        menuButtonMapOptions.onClick {
            WrapPopup(stage, mapOptionsTable)
        }
        menuButtonInvite.onClick {
            WrapPopup(stage, LobbyInviteTable(lobbyUUID, this as BaseScreen))
        }
        menuButtonStartGame.onActivation {
            val lobbyStartResponse = InfoPopup.load(stage) {
                game.onlineMultiplayer.api.lobby.startGame(lobbyUUID)
            }
            if (lobbyStartResponse != null) {
                startGame(lobbyStartResponse)
            }
        }

        bottomButtonLeave.keyShortcuts.add(KeyCharAndCode.ESC)
        bottomButtonLeave.keyShortcuts.add(KeyCharAndCode.BACK)
        bottomButtonLeave.onActivation {
            InfoPopup.load(stage) {
                if (game.onlineMultiplayer.api.account.get()!!.uuid == owner.uuid) {
                    game.onlineMultiplayer.api.lobby.close(lobbyUUID)
                } else {
                    game.onlineMultiplayer.api.lobby.leave(lobbyUUID)
                }
            }
            game.popScreen()
        }
        bottomButtonSocial.onActivation {
            ToastPopup("The social feature has not been implemented yet.", stage)
        }
        bottomButtonHelp.keyShortcuts.add(Input.Keys.F1)
        bottomButtonHelp.onActivation {
            ToastPopup("The help feature has not been implemented yet.", stage)
        }

        recreate()
        Concurrency.run {
            refresh()
        }
    }

    private class WrapPopup(stage: Stage, other: Actor, action: (() -> Unit)? = null) : Popup(stage) {
        init {
            innerTable.add(other).center().expandX().row()
            addCloseButton(action = action)
            open()
        }
    }

    /**
     * Refresh the cached data for this lobby and its chat room and recreate the screen
     */
    private suspend fun refresh() {
        chatMessageList.triggerRefresh()

        val lobby = try {
            game.onlineMultiplayer.api.lobby.get(lobbyUUID)
        } catch (e: Exception) {
            Log.error("Refreshing lobby %s failed: %s", lobbyUUID, e)
            null
        }
        if (lobby != null) {
            currentPlayers = lobby.currentPlayers
            lobbyName = lobby.name
            Concurrency.runOnGLThread {
                recreate()
            }
        }
    }

    /**
     * Recreate the screen including some of its elements
     */
    fun recreate(): BaseScreen {
        val table = Table()

        val players = VerticalGroup()
        val playerScroll = AutoScrollPane(lobbyPlayerList, skin)
        playerScroll.setScrollingDisabled(true, false)

        val optionsTable = Table().apply {
            align(Align.center)
        }
        optionsTable.add(menuButtonGameOptions).row()
        optionsTable.add(menuButtonMapOptions).padTop(10f).row()
        optionsTable.addSeparator(skinStrings.skinConfig.baseColor.brighten(0.1f), height = 0.5f).padTop(25f).padBottom(25f).row()
        optionsTable.add(menuButtonInvite).padBottom(10f).row()
        optionsTable.add(menuButtonStartGame).row()

        val chatTable = ChatTable(chatMessageList, true)
        val menuBar = Table()
        menuBar.align(Align.bottom)
        menuBar.add(bottomButtonLeave).pad(20f)
        menuBar.add().fillX().expandX()
        menuBar.add(bottomButtonSocial).pad(5f)  // lower padding since the help button has padding as well
        menuBar.add(bottomButtonHelp).padRight(20f)

        // Construct the table which makes up the whole lobby screen
        table.row()
        val topLine = HorizontalGroup()
        topLine.addActor(Container(screenTitle).padRight(10f))
        topLine.addActor(changeLobbyNameButton)
        table.add(topLine.pad(10f).center()).colspan(3).fillX()
        table.addSeparator(skinStrings.skinConfig.baseColor.brighten(0.1f), height = 0.5f).width(stage.width * 0.85f).padBottom(15f).row()
        table.row().expandX().expandY()
        table.add(playerScroll).fillX().expandY().prefWidth(stage.width * 0.6f).padLeft(5f)
        // TODO: The options table is way to big, reduce its width somehow
        table.add(optionsTable).prefWidth(stage.width * 0.1f).padLeft(0f).padRight(0f)
        // TODO: Add vertical horizontal bar like a left border for the chat screen
        // table.addSeparatorVertical(skinStrings.skinConfig.baseColor.brighten(0.1f), width = 0.5f).height(0.5f * stage.height).width(0.1f).pad(0f).space(0f)
        table.add(chatTable).fillX().expandY().prefWidth(stage.width * 0.5f).padRight(5f)
        table.addSeparator(skinStrings.skinConfig.baseColor.brighten(0.1f), height = 0.5f).width(stage.width * 0.85f).padTop(15f).row()
        table.row().bottom().fillX().maxHeight(stage.height / 8)
        table.add(menuBar).colspan(3).fillX()
        table.setFillParent(true)
        stage.clear()
        stage.addActor(table)
        return this
    }

    /**
     * Build a new [GameInfo], upload it to the server and start the game
     */
    private fun startGame(lobbyStart: StartGameResponse) {
        Log.debug("Starting lobby '%s' (%s) as game %s", lobbyName, lobbyUUID, lobbyStart.gameUUID)
        val popup = Popup(this)
        Concurrency.runOnGLThread {
            popup.addGoodSizedLabel("Working...").row()
            popup.open(force = true)
        }

        Concurrency.runOnNonDaemonThreadPool {
            val gameInfo = try {
                GameStarter.startNewGame(gameSetupInfo, lobbyStart.gameUUID.toString())
            } catch (exception: Exception) {
                Log.error(
                    "Failed to create a new GameInfo for game %s: %s",
                    lobbyStart.gameUUID,
                    exception
                )
                exception.printStackTrace()
                Concurrency.runOnGLThread {
                    popup.apply {
                        reuseWith("It looks like we can't make a map with the parameters you requested!")
                        row()
                        addGoodSizedLabel("Maybe you put too many players into too small a map?").row()
                        addCloseButton()
                    }
                }
                return@runOnNonDaemonThreadPool
            }

            Log.debug("Successfully created new game %s", gameInfo.gameId)
            Concurrency.runOnGLThread {
                popup.reuseWith("Uploading...")
            }
            runBlocking {
                InfoPopup.wrap(stage) {
                    game.onlineMultiplayer.createGame(gameInfo)
                    true
                }
                Log.debug("Uploaded game %s", lobbyStart.gameUUID)
            }
            Concurrency.runOnGLThread {
                popup.close()
                game.loadGame(gameInfo)
            }
        }
    }

    override fun lockTables() {
        Log.error("Not yet implemented")
    }

    override fun unlockTables() {
        Log.error("Not yet implemented")
    }

    override fun updateTables() {
        Log.error("Not yet implemented")
    }

    override fun updateRuleset() {
        Log.error("Not yet implemented")
    }

    private fun update() {
        Log.error("Not yet implemented")
    }

}