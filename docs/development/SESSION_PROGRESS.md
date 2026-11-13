# Active session — Minecraft ports and recovery (November 13, 2026)

User explicitly says finish ALL16 suite targets; do not stop at compile milestones. Latest steering requests activeGittracking afterfilesystemcorruption. Keep working autonomously; no pushes/tags/publishing. Localmilestonecommits nowauthorized. No subagents or imagegeneration. NeverOSinput or user'sMinecraftprocess control. Warn immediatelybeforelaunchinganyownedGUIclient.

## Repository / builds
- Workspace A:/Projects/The Experiment Lab/Minecraft/codex; Linuxcwd /mnt/a/projects/the experiment lab/minecraft/codex. SourcesmaintainedHERE. AGENTSread. Usebuild-smart.py exclusively forGradle.
- OwnedC:buildmirror C:/Users/video/AppData/Local/Temp/codex-ports-20261108 (.codex-owned-build-mirror marker). Avoidscache/path/full-driveproblems. WindowsJava25configured; pertargetJava17/21/25.
- Nativecmd /mnt/c/Windows/System32/cmd.exe requires exec_command require_escalated. Run `python scripts\build-port-mirror.py <smartcommand> --offline`. It copiesmaintainedroots only (no .git/worlds/runs) thenrunsnativewrapper. `--run-script test-server.py ...` runstestharnessinmirror.
- `smoke-server:<EXACTtarget>` only. Multipleversions use `matrix:servers-runtime:1.20.1 -Pcodex_server_smoke`. Allworlds/profilesunderownedmirror/runs. NeveruserPrism/saves.
- Smartservererrorcanprintgiantnativeadvancement dumps. Forpolls manuallytruncate toolresult.output via .slice beforetext(); inspectlogs withPython dedup/errorheading limits.

## Git / recovered files
- Branch codex/ports-and-recovery-2026-11-12. UserrequestedactiveGit. ffd2acf recoverycheckpoint413files;54e82b2 legacyAPI/dataformatmilestone. Nothingpushed. Futurecommits smallmeaningfulmilestones, keepC:bundlesupdated.
- Recovered7NULL-filledsourcefiles withverifiedmatchingoldbytes frombefore-stonecutter.zip/packagedJAR. Corruptoriginals ignoredruns/multiversion-baseline/corrupt-source-20261112.
- Gitlooseobjectsdamagedtoo: restored2historicalobjectsfromreadonlyGitHubclonewithexactSHA;thirdunreachablefromallrefs/reflogs/index quarantinedwithbytesretainedC:. gitfsckfullPASS. Nativeoldunreachableobjectsleftalone.
- C:/Users/video/AppData/Local/MC-Codex-backups containsverifiedMC-Codex-recovery-20261112.bundle(ffd2acf), MC-Codex-checkpoint-54e82b2.bundle, checksumJSON, damagedobjectcopies,readonlyoriginmirror. GIT_RECOVERY.md details. Currentharness+laterrewardfix/modelworkuncommitted, preserveit.

## Evidence (distinguish each)
- TENmodern26.x compile/package/artifactinspection10/10PASS fromNov7.26.1.2+26.3 BOTHloaders simulated169initial+11restart+7independent PASS. 26.3bundled31each;26.2bundled29each. These predaterecentcanonicalJSON/recipechanges; runmodernregressionbeforefinal.
- FOUR1.21/1.21.1 Fabric+NeoForge compile andsimulated169initial+11restart+7independent PERloaderPERversion PASS (748checks). Reportsmirror/runs/validation/<target>/results-*.json.
- BOTH1.20.1 Fabric/Forge compileSUCCESS; BOTHdedicatedstartup/reloadSmokePASSwithzeroerrors. Forge retry55059PASSafterminVersion0.8config. Profilefailedbeforeiconrecoverypreservedunder1.20.1-fabric-before-icon-recovery.
- 1.20.1Fabric firstgameplay144PASS/25FAIL: NativeitemStackrequiresCountnotcount,missingtagnotrejected. FixedItemFormats.nativeStack/LegacyItems+explicitSubmissionPlan taglookup. LATESTrun28023 `--run-script test-server.py all --minecraft-version1.20.1 --reset --offline`: Fabric169initial+11restart+7independent PASS. Forge159initialPASS/10FAIL, all failures in embedded-packet observation/navigation responses; restart/independent not run after failure. Investigate Forge outbound transport/observer before claiming parity.
- Recipe1.20.1 native matching PASS19/19 EACH Fabric/Forge (session24843). Recipe1.21.1Fabric14PASS/4FAIL: all four cooking output quantity checks fail; investigate native cooking count support and validation. NeoForge not run after failure. Session52028 exited1.
- LastactualGUIreview ONLY26.1.2Fabric29checks, oldtwo-clientbaselineprofiles. NOnewertargetorlegacyGUIproofyet. User'sPrism26.1.2stillpreviousreviewedJAR, NOTupdatedthisround. No desktopinputused.

## Implemented port architecture
- NativeStonecutter suitebuild, canonicalcommon main/client+loader sources; gradle/source-compat.gradle exactAPIrenamegroups, legacy-onlycompatroots. No wholemodsourcecopies or disabledquestfeatures.
-1.21family: legacyinput/pose/widget adapters, NBTreceiptmixins,classicpermissions/registries, oldJEIcallbacks/getters, correctNeoForgeMODbus, AdvancementResetMixin old4argperform.
-1.20nativeAdvancement replacesHolder/Node throughversionrules; LegacyAdvancements Gsoncodecs/nativepreviewlist, Requirements120 preservesAND/OR/nativewire, boundedStreamCodec/ByteBufCodecs+CustomPacketPayload+nativeArchitectury9NetworkManager facade. GsonCodec120 preservesnativeJsonNulls bypassingDFU6PASSTHROUGH RegistryOpsbug. LegacyItems mapsknowncomponentproperties/nativeNBT, comparescustomnamessemantically, preservesremainingheldSNBT; unknownunsupportedpropertiesexpliciterrors.
- ResourceFormats handles1.20pluralfolders, nativeiconitemfield(retainsid), generatedpredicateitemarrays/tags+readonlyreverse. BundledExamplesmapsTARGETpathsbutkeepsresource/ledgerkeys; nativevanillabody+displayoverlay, onlyuntouchedownedentriesauto-upgrade/backup. NativegetOrThrowDFUoldsignature, Java17collections, oldGsonsizechecks.
- RecipeFormats preservesunknownfields/nativeoldobjectingredients/1.20itemoutputfield+cookingstringone-itemconstraint; serverreadsnormalizedauthoringview,validates/writesnativeformat, nativefolderlisting;GUIordinaryingredienticons; 1.20cookingcountlocked1. Rawunsupportedoutputcollisionadvancedfallback.
-1.20coreGUIclassicAtlas/nine-slice renderer+nativegradientbackdrop, Checkboxbuilder, mousewheel3->4bridge, floatHUD. Nativeatlascoordsinspectedbytecode. Needsvisualreviewselectedtab/nativeboxslicing.
- NEWuncommitted legacybagvisualsupport: gradle/compat/resources/classic/assets/codex/models/item/treasure_bag.json; gradle/resource-compat addsoldmodel+client.TreasureBagModelMixin for<1.21.4; mixin delegatesappearance toselectednativebakeditemmodel, specialentityrenderersfallbackbag. Needscompile+actualGUI. No generatedrasterimages.

## Harness specifics / next exact work
1. Classic full suite28023 finished: Fabric187/187PASS, Forge159PASS/10FAIL (embedded outbound packet observations). FixANYrealfailure. ForgeScenarios+META-INFtestmod added; scriptstest-server/test-recipes/test-bundled-examples nowacceptForgeandall1.20resolvesFabric/Forge. FakeForgeconnectionsmightneedsnativeconfiguration; donotclaim2actualclients.
2. LegacyFixtures convertsONLYdisposabletestpackfolders/icons/criteria; nativevanillafixturebasesfromrunninggame,MCfunctions movedplural. Alloriginal169scenariosreused. Nativeplayerctor/placeNewPlayer+oldpacketobserver, Java17threads,ScenarioBuffers; test-onlylegacyCraftingInput/SingleRecipeInputrealcontainers. Core1.20Source tests packetdecodeFacade.decode added; preservefullcoverage.
3. Classic recipe profile finished19/19 eachloader, including unsupported cooking-count rejection. Investigate1.21.1 cooking output count failures before broader recipe validation.
4. Run1.20bundled31checks/loader; mayneedadjustharnesshardcodedresourcepaths/nativeJSONcomparisons.
5. IMPORTANT: implementlargepackettransportusingexistingArchitectury9SplitPacketTransformer, notnewprotocol. Facadecurrentlyrawoldnativechannel; maxC2S32767/S2C1MBcouldbreaklargeeditorpacks. Builtintransformer canbeappliedmanuallyoutbound/inboundbecausecodec-onlyS2Cregistrationdoesnotregisternativeoutboundtransformers. Inspectprimarysource first, enforcebounds/malformedsplit safety.
6. All16compile+package+artifactverification(latestcanonicalchanges), representative26.xremainingserverchecks. CopyfinalartifactsfromownedC:mirror toworkspaceonlyaftervalidation.
7. ActualGUIjourneys/prodclientchecks: extendgradle/validation nativepreparationtocodexClientTest (currentlyserveronly), reuseexistingtest-clients.pywidgetdriver, orownedcompactactual-clientprobe. WARNbeforewindowopens; noOSmouse/keyboard. Needlegacy+26.3GUI, delivery/branch/recipe/bag/rendering/network. CurrentproductionclienthelperonlystartupnotGUIjourney.
8. UpdateMULTIVERSION/TESTING/README,evidencepagesandthischeckpoint, commitmilestones+offdrivebundles. No prematurefinalclaim.

Historicaldetails archived in session-archive/SESSION_PROGRESS-through-2026-11-13.md.
