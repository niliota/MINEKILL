using BepInEx;

namespace Minekill
{
    /// <summary>
    /// MINEKILL on the ULTRAKILL side.
    ///
    /// Milestone 3: open the shared file, complete the handshake with Minecraft, and
    /// keep the heartbeat alive. Nothing else yet. The point of this stage is to
    /// prove both processes can see each other before any feature depends on it.
    ///
    /// NOTE: this MUST derive from BepInEx.BaseUnityPlugin, not from
    /// UnityEngine.MonoBehaviour. BepInEx 5's Chainloader.ToPluginInfo rejects any
    /// type that is not a subtype of BaseUnityPlugin, and it does so SILENTLY: the
    /// subtype check returns null before the code that warns about a missing
    /// [BepInPlugin] attribute, so a MonoBehaviour-derived plugin produces
    /// "0 plugins to load" with no warning and no error at all.
    ///
    /// This class only bootstraps. The bridge itself lives in BridgeHost, on a
    /// HideAndDontSave object, because the BepInEx plugin object does not survive
    /// ULTRAKILL's scene change at boot.
    /// </summary>
    [BepInPlugin(Guid, "MINEKILL ULTRAKILL side", "0.1.0")]
    public class Plugin : BaseUnityPlugin
    {
        public const string Guid = "dev.minekill.ultrakill";

        private void Awake()
        {
            if (BridgeHost.Create() == null)
            {
                // Bridge unavailable. ULTRAKILL stays completely vanilla; every
                // feature checks for the host and does nothing without it.
                return;
            }
            Logger.LogInfo("host installed");
        }
    }
}