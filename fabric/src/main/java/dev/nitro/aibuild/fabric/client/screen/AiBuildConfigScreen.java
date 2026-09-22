package dev.nitro.aibuild.fabric.client.screen;

import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.config.BuildMode;
import dev.nitro.aibuild.core.config.ProviderSettings;
import dev.nitro.aibuild.core.llm.LlmClient;
import dev.nitro.aibuild.core.llm.LlmClientFactory;
import dev.nitro.aibuild.core.llm.LlmException;
import dev.nitro.aibuild.core.llm.Provider;
import dev.nitro.aibuild.fabric.AiBuildMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings screen.
 *
 * <p>Nothing here has to be typed exactly right. The provider and the model are
 * both chosen from a list of buttons, since getting either slightly wrong is the
 * difference between working and a baffling 404.
 *
 * <p>This edits the config file on this machine. In singleplayer that is the one
 * in use. Connected to someone else's server it is not, and the screen says so
 * rather than pretending the change took effect.
 */
public final class AiBuildConfigScreen extends Screen {

    /** Shown in place of a key that is already saved, so it is never put on screen. */
    private static final String MASK = "................";

    private static final int FIELD_WIDTH = 220;
    private static final int LABEL_WIDTH = 90;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final AiBuildConfig config = AiBuildMod.config();

    private EditBox apiKeyBox;
    private boolean apiKeyEditable;
    private EditBox modelBox;
    private String pendingModelList;
    private boolean fetchingModels;

    public AiBuildConfigScreen(Screen parent) {
        super(Component.literal("AIBuild Settings"));
        this.parent = parent;
    }

    private Provider provider() {
        return config.activeProvider();
    }

    private ProviderSettings settings() {
        return config.settingsFor(provider());
    }

    @Override
    protected void init() {
        Provider provider = provider();
        ProviderSettings settings = settings();

        int left = (width - (LABEL_WIDTH + FIELD_WIDTH)) / 2;
        int fieldX = left + LABEL_WIDTH;
        int y = 30;

        addRenderableWidget(new StringWidget(0, 12, width, 12,
                getTitle().copy().withStyle(ChatFormatting.AQUA), font));

        if (!isEditingLiveConfig()) {
            addRenderableWidget(new StringWidget(0, y, width, 12, Component.literal(
                            "You are on a remote server. These settings apply to your own games only.")
                    .withStyle(ChatFormatting.YELLOW), font));
            y += 16;
        }

        // Provider, chosen from a list rather than typed.
        addRenderableWidget(new StringWidget(left, y + 5, LABEL_WIDTH, 10,
                Component.literal("Provider"), font));
        addRenderableWidget(Button.builder(
                        Component.literal(provider.displayName()),
                        button -> minecraft.setScreenAndShow(providerPicker()))
                .bounds(fieldX, y, FIELD_WIDTH, 20).build());
        y += ROW_HEIGHT;

        // API key. Blank and disabled when the provider does not use one.
        addRenderableWidget(new StringWidget(left, y + 5, LABEL_WIDTH, 10,
                Component.literal("API key"), font));
        apiKeyBox = new EditBox(font, fieldX, y, FIELD_WIDTH, 20, Component.literal("API key"));
        apiKeyBox.setMaxLength(512);
        apiKeyEditable = true;
        if (!provider.requiresApiKey()) {
            apiKeyBox.setValue("");
            apiKeyBox.setEditable(false);
            apiKeyEditable = false;
            apiKeyBox.setHint(Component.literal("not needed for this provider"));
        } else {
            boolean fromEnvironment = !provider.apiKeyEnvVar().isBlank()
                    && System.getenv(provider.apiKeyEnvVar()) != null
                    && !System.getenv(provider.apiKeyEnvVar()).isBlank();
            if (fromEnvironment) {
                apiKeyBox.setValue("");
                apiKeyBox.setEditable(false);
                apiKeyEditable = false;
                apiKeyBox.setHint(Component.literal("set by " + provider.apiKeyEnvVar()));
            } else {
                apiKeyBox.setValue(settings.apiKeyOrEmpty().isEmpty() ? "" : MASK);
                apiKeyBox.setHint(Component.literal("paste your key"));
            }
        }
        addRenderableWidget(apiKeyBox);
        y += ROW_HEIGHT;

        // Model, typeable but with a picker next to it.
        addRenderableWidget(new StringWidget(left, y + 5, LABEL_WIDTH, 10,
                Component.literal("Model"), font));
        modelBox = new EditBox(font, fieldX, y, FIELD_WIDTH - 74, 20, Component.literal("Model"));
        modelBox.setMaxLength(128);
        modelBox.setValue(settings.modelOrDefault(provider.defaultModel()));
        addRenderableWidget(modelBox);

        addRenderableWidget(Button.builder(
                        Component.literal(fetchingModels ? "..." : "Choose"), button -> fetchModels())
                .bounds(fieldX + FIELD_WIDTH - 70, y, 70, 20).build());
        y += ROW_HEIGHT + 6;

        if (pendingModelList != null) {
            addRenderableWidget(new StringWidget(0, y, width, 10,
                    Component.literal(pendingModelList).withStyle(ChatFormatting.GRAY), font));
            y += 16;
        }

        // Build mode, a toggle rather than something to type.
        addRenderableWidget(new StringWidget(left, y + 5, LABEL_WIDTH, 10,
                Component.literal("Build mode"), font));
        addRenderableWidget(CycleButton.builder(
                        (BuildMode mode) -> Component.literal(mode.displayName()), config.activeBuildMode())
                .withValues(BuildMode.values())
                .displayOnlyValue()
                .create(fieldX, y, FIELD_WIDTH, 20, Component.literal("Build mode"),
                        (button, mode) -> config.buildMode = mode.id()));
        y += ROW_HEIGHT;

        addRenderableWidget(Button.builder(Component.literal("Build settings..."),
                        button -> minecraft.setScreenAndShow(new BuildSettingsScreen(this)))
                .bounds(fieldX, y, FIELD_WIDTH, 20).build());

        int footerY = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveAndClose())
                .bounds(width / 2 - 104, footerY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),
                        button -> minecraft.setScreenAndShow(parent))
                .bounds(width / 2 + 4, footerY, 100, 20).build());
    }

    /**
     * True when this client is also running the world, which is the only case
     * where editing the local file changes what actually builds.
     */
    private boolean isEditingLiveConfig() {
        Minecraft client = Minecraft.getInstance();
        return client.hasSingleplayerServer() || client.getCurrentServer() == null;
    }

    private Screen providerPicker() {
        List<PickerScreen.Choice> choices = new ArrayList<>();
        for (Provider candidate : Provider.values()) {
            boolean ready = !candidate.requiresApiKey()
                    || !LlmClientFactory.resolveApiKey(candidate, config.settingsFor(candidate)).isBlank();
            String note = candidate.requiresApiKey()
                    ? (ready ? "[key set]" : "[needs key]")
                    : "[no key]";
            choices.add(new PickerScreen.Choice(
                    candidate.id(), candidate.displayName(), note, candidate == provider()));
        }

        return new PickerScreen(this, "Choose a provider", choices, "", id -> {
            config.provider = id;
            // The key and model fields belong to the provider, so reopen with the new one.
            pendingModelList = null;
        });
    }

    private void fetchModels() {
        if (fetchingModels) {
            return;
        }
        // Take the typed key now, so listing works before the first save.
        applyApiKeyField();

        fetchingModels = true;
        pendingModelList = "Asking " + provider().displayName() + "...";
        rebuildWidgets();

        AiBuildMod.executor().submit(() -> {
            String error = null;
            List<String> ids = List.of();
            try {
                LlmClient client = LlmClientFactory.create(config);
                ids = client.listModels();
            } catch (LlmException | LlmClientFactory.NotConfiguredException e) {
                error = e.getMessage();
            }

            List<String> found = ids;
            String failure = error;
            Minecraft.getInstance().execute(() -> {
                fetchingModels = false;
                if (failure != null) {
                    pendingModelList = failure;
                    rebuildWidgets();
                    return;
                }
                if (found.isEmpty()) {
                    pendingModelList = "This provider does not list its models. Type one instead.";
                    rebuildWidgets();
                    return;
                }
                pendingModelList = null;
                List<PickerScreen.Choice> choices = new ArrayList<>();
                String currentModel = modelBox.getValue().trim();
                for (String id : found) {
                    choices.add(new PickerScreen.Choice(id, id, "", id.equals(currentModel)));
                }
                minecraft.setScreenAndShow(new PickerScreen(this,
                        "Choose a model", choices, "", id -> settings().model = id));
            });
        });
    }

    /** Writes the key field back, leaving a saved key alone if it was not retyped. */
    private void applyApiKeyField() {
        if (apiKeyBox == null || !apiKeyEditable) {
            return;
        }
        String typed = apiKeyBox.getValue().trim();
        if (typed.equals(MASK)) {
            return;
        }
        settings().apiKey = typed;
    }

    private void saveAndClose() {
        applyApiKeyField();
        if (modelBox != null) {
            settings().model = modelBox.getValue().trim();
        }

        boolean saved = AiBuildMod.saveConfig();
        if (saved) {
            // Reread so the scheduler and limits pick up anything that changed.
            AiBuildMod.reloadConfig();
        } else {
            AiBuildMod.LOGGER.warn("Could not write the AIBuild config from the settings screen.");
        }
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
