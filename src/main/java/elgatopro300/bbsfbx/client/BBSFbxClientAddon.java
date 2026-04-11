package elgatopro300.bbsfbx.client;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXModelLoader;
import mchorse.bbs_mod.addons.BBSClientAddon;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.events.register.RegisterModelLoadersEvent;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class BBSFbxClientAddon extends BBSClientAddon
{
    public static final Logger LOGGER = LoggerFactory.getLogger("bbs-fbx-addon-client");

    @Override
    @Subscribe
    public void registerModelLoaders(RegisterModelLoadersEvent event)
    {
        event.registerLoader(new FBXModelLoader());
        event.registerRelodableSuffix(".fbx");
        LOGGER.info("FBX model loader registered");
    }

    @Override
    @Subscribe
    public void registerL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> List.of(
            new Link("bbs_fbx", "lang/" + L10n.DEFAULT_LANGUAGE + ".json"),
            new Link("bbs_fbx", "lang/" + lang + ".json")
        ));
        try
        {
            event.l10n.reload();
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to reload BBS L10n", e);
        }
    }
}
