"""Build the separate Codex Survival playtest content. Never edits a live Prism instance."""
from pathlib import Path
import json,shutil,zipfile
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'modpacks/codex-survival/overrides';PACK=OUT/'codex/advancements'
NS='codex_survival';entries=[];quests={};claims={};submissions={};stats={};positions={}
def write(path,value):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
def inv(item,count=1):return {'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':item,'count':{'min':count}}]}}
def kill(entity):return {'trigger':'minecraft:player_killed_entity','conditions':{'entity':[{'condition':'minecraft:entity_properties','entity':'this','predicate':{'type':entity}}]}}
def visit(structure):return {'trigger':'minecraft:location','conditions':{'player':[{'condition':'minecraft:entity_properties','entity':'this','predicate':{'location':{'structures':structure}}}]}}
def dimension(name):return {'trigger':'minecraft:changed_dimension','conditions':{'to':name}}
def eat(item):return {'trigger':'minecraft:consume_item','conditions':{'item':{'items':item}}}
def add(group,key,title,text,icon,criteria=None,parent=None,reward=None,challenge=False,narrative=None):
 # Ordinary objectives need notifications regardless of their frame style. Introductions
 # stay quiet, and managed delivery objectives use their exact submission receipts.
 notify=bool(criteria) and any(c.get('trigger')!='minecraft:impossible' for c in criteria.values())
 ident=f'{NS}:{group}/{key}';display={'icon':{'id':icon},'title':title,'description':text,'show_toast':notify,'announce_to_chat':False,'frame':'challenge' if challenge else 'task'}
 doc={'display':display,'criteria':criteria or {'arrive':{'trigger':'minecraft:tick'}}}
 same=[e for e in entries if e['group']==group]
 if same:doc['parent']=f'{NS}:{group}/{parent or "root"}'
 else:display['background']='minecraft:block/stone'
 write(PACK/f'data/{NS}/advancement/{group}/{key}.json',doc)
 index=len(same);positions[ident]={'x':0 if not index else 80*(1+(index-1)//4),'y':75 if not index else 10+50*((index-1)%4)}
 entries.append({'id':ident,'group':group,'title':title,'description':text,'parent':doc.get('parent')})
 if reward:claims[ident]={'experience':reward.get('xp',5),'codex_items':[{'id':item,'count':count} for item,count in reward.get('items',[])]}
 if narrative:quests[ident]={'dialogue':{'intro':narrative}}
 return ident
R=lambda *items:{'items':list(items),'xp':5}
def obtain(g,key,title,text,item,parent=None,count=1,reward=None,challenge=False):return add(g,key,title,text,item,{'obtain':inv(item,count)},parent,reward,challenge)
def root(g,title,text,icon):
 ident=add(g,'root',title,text,icon,narrative=text+'\n\nPin a goal to track it, and look for a reward dot when there is something to claim.')
 quests[ident]['quest_tab']=True
 return ident
def delivery(g,key,title,text,items,rewards,parent=None):
 ident=add(g,key,title,text,items[0][0],{'deliver':{'trigger':'minecraft:impossible'}},parent,R(*rewards))
 quests[ident]={'acceptance':True,'repeat':{'enabled':True,'cooldown_seconds':300,'scope':'personal'},'dialogue':{'intro':text+'\n\nAccept before submitting. Partial deliveries count and the receipt lists what was consumed. Another delivery is available five minutes after claiming.'}}
 submissions[ident+'#deliver']={'items':[{'id':item,'count':count} for item,count in items]}
# 8: a short opening, with an optional surplus exchange rather than a mandatory tutorial chain.
g='start';root(g,'Start Here','A survival pack about useful preparation and expeditions. Collect twelve different End Remastered eyes, reach the End, defeat the dragon, and assemble the Survival Star. Other sections are open from the start.','minecraft:book')
add(g,'wood','Something to Work With','Obtain eight logs of any type. Set up wherever you want; there is no required base location.','minecraft:oak_log',{'logs':inv('#minecraft:logs',8)},reward=R(('minecraft:torch',4)))
obtain(g,'table','A Working Surface','Obtain a crafting table. JEI shows recipes, including the pack changes.','minecraft:crafting_table',parent='wood')
obtain(g,'iron','First Iron','Obtain four iron ingots for equipment and tools.','minecraft:iron_ingot',count=4,reward=R(('minecraft:coal',4)))
add(g,'bed','Somewhere to Return','Obtain any bed and establish a respawn point before a long trip.','minecraft:red_bed',{'bed':inv('#minecraft:beds')})
obtain(g,'shield','Bring a Shield','Obtain a shield. Combo adds timed parries and stamina; its Mod Menu settings explain the controls. Practice blocking before your next expedition.','minecraft:shield',parent='iron',reward=R(('minecraft:cooked_beef',4)))
obtain(g,'backpack','Pack for the Trip','Obtain a Backpacked backpack. Its pack recipe uses leather, canvas and bronze; check JEI.','backpacked:backpack',parent='iron',reward=R(('minecraft:lead',2)))
delivery(g,'supplies','Spare Building Supplies','Optional exchange: deliver 16 cobblestone and 8 oak logs for 8 coal. Your equipment progression does not depend on this contract.',[('minecraft:cobblestone',16),('minecraft:oak_log',8)],[('minecraft:coal',8)])
# 12: Spelunker Update is the main materials progression, not a decorative extra.
g='workshop';root(g,'Underground Workshop','Find the Spelunker Update ores, build a foundry, and turn underground discoveries into expedition equipment. Check JEI for foundry and smithing recipes.','spelunkery:foundry')
obtain(g,'tin','Tin','Smelt tin ore into an ingot. Keep some copper for bronze.','spelunkery:tin_ingot',reward=R(('minecraft:coal',4)))
obtain(g,'nickel','Nickel','Find and smelt nickel. It is used in plating and alloys.','spelunkery:nickel_ingot')
obtain(g,'silver','Silver','Find and smelt silver. It becomes useful for electrum.','spelunkery:silver_ingot')
obtain(g,'foundry','The Foundry','Build a foundry. It processes the mod\'s alloys using ingredients and lava.','spelunkery:foundry',parent='tin',reward=R(('minecraft:bucket',1)))
obtain(g,'bronze','First Alloy','Produce four bronze ingots. Check JEI for the foundry ingredients.','spelunkery:bronze_ingot',parent='foundry',count=4,reward=R(('minecraft:torch',12)))
obtain(g,'bronze_pick','Put Bronze to Work','Obtain a bronze pickaxe. Put it to work on your next mining trip.','spelunkery:bronze_pickaxe',parent='bronze')
obtain(g,'helmet','A Miner\'s Helmet','Obtain a miner\'s helmet before your next deep expedition.','spelunkery:miners_helmet',parent='bronze',reward=R(('minecraft:cooked_beef',4)))
obtain(g,'plating','Nickel Plating','Produce nickel plating and inspect the armor upgrades it enables.','spelunkery:nickel_plating',parent='nickel')
obtain(g,'invar','Invar','Produce four invar ingots. Some End-eye recipes now use the alloy.','spelunkery:invar_ingot',parent='foundry',count=4,challenge=True,reward=R(('minecraft:experience_bottle',3)))
obtain(g,'electrum','Electrum','Produce four electrum ingots. It connects equipment, enchanting and eye crafting.','spelunkery:electrum_ingot',parent='silver',count=4)
obtain(g,'lens','Look More Closely','Obtain a prospector lens and take it on a mining trip.','spelunkery:prospector_lens',parent='helmet',reward=R(('minecraft:golden_carrot',4)))
# 10: fishing has both equipment and actual catches.
g='fishing';root(g,'Fishing','Fishing Frenzy adds species, bait and equipment. Keep a few different catches: they contribute to the crafted Exotic Eye. Check bait and rod tooltips for the controls.','minecraft:fishing_rod')
obtain(g,'rod','A Fishing Rod','Obtain a fishing rod and find open water.','minecraft:fishing_rod',reward=R(('minecraft:string',3)))
obtain(g,'bait','Bait','Obtain worm bait. Use the rod and bait controls described by Fishing Frenzy.','fishingfrenzy:worm_bait',parent='rod')
add(g,'catch','First Catch','Reel in a fish or fishing loot with a rod.','minecraft:cod',{'catch':{'trigger':'minecraft:fishing_rod_hooked'}},parent='rod',reward=R(('minecraft:experience_bottle',2)))
obtain(g,'carp','Carp','Catch a carp and keep a few for crafting and supplies.','fishingfrenzy:carp',parent='catch')
obtain(g,'bream','Bream','Catch or obtain a bream. Keep one for the Exotic Eye recipe.','fishingfrenzy:bream',parent='catch')
obtain(g,'deluxe_rod','Better Tackle','Obtain the deluxe fishing rod. Its pack recipe uses bronze and string.','fishingfrenzy:deluxe_fishing_rod',parent='bait',reward=R(('fishingfrenzy:worm_bait',8)))
obtain(g,'deluxe_bait','Better Bait','Obtain deluxe bait. Check its tooltip before using it.','fishingfrenzy:deluxe_bait',parent='deluxe_rod')
obtain(g,'shell','A Nautilus Shell','Obtain a nautilus shell by fishing, trading or another legitimate source.','minecraft:nautilus_shell',parent='catch',reward=R(('minecraft:glass_bottle',6)))
delivery(g,'fish_exchange','Fish for Supplies','Optional exchange: deliver 4 carp for 4 iron ingots. Keep your eye-crafting ingredients before submitting. This exchange is optional.',[('fishingfrenzy:carp',4)],[('minecraft:iron_ingot',4)],parent='carp')
# 8: food supports expeditions; no enforced nutrition grind.
g='provisions';root(g,'Food and Supplies','Build a useful kitchen with Farmer\'s Delight. These goals are optional preparation; you do not need a checklist of meals to enter the End.','farmersdelight:cooking_pot')
obtain(g,'knife','A Kitchen Knife','Obtain a flint knife. Use it to prepare ingredients at a cutting board.','farmersdelight:flint_knife',reward=R(('minecraft:flint',3)))
obtain(g,'board','Cutting Board','Obtain a cutting board. Check JEI for ingredients you can cut.','farmersdelight:cutting_board',parent='knife')
add(g,'crops','A More Varied Garden','Obtain cabbage, tomato and rice. Grow them so expedition food is renewable.','farmersdelight:cabbage',{'cabbage':inv('farmersdelight:cabbage'),'tomato':inv('farmersdelight:tomato'),'rice':inv('farmersdelight:rice')},reward=R(('minecraft:bone_meal',12)))
obtain(g,'pot','Cooking Pot','Obtain a cooking pot and set up its heat source.','farmersdelight:cooking_pot',parent='board',reward=R(('minecraft:bowl',8)))
add(g,'meal','A Proper Meal','Eat Farmer\'s Delight beef stew. Consult JEI for the cooking-pot recipe.','farmersdelight:beef_stew',{'eat':eat('farmersdelight:beef_stew')},parent='pot',reward=R(('minecraft:glass_bottle',6)))
obtain(g,'fish_stew','Use the Catch','Prepare or obtain fish stew. Check JEI for suitable fish.','farmersdelight:fish_stew',parent='pot')
delivery(g,'meal_exchange','Meals for the Road','Optional exchange: deliver 2 beef stews for 8 arrows and 4 coal. Finish equipping yourself before donating food.\n',[('farmersdelight:beef_stew',2)],[('minecraft:arrow',8),('minecraft:coal',4)],parent='meal')
# 8: grounded combat and equipment, with honest Combo instructions.
g='equipment';root(g,'Equipment','Pick equipment that suits the expedition. Combo adds melee finishers, parries, stamina and dodging. Prepare for close fights and long journeys.','minecraft:iron_sword')
add(g,'hostiles','Know Your Opponents','Defeat a zombie, skeleton and spider. Try Combo\'s timing and shield controls while fighting.','minecraft:iron_sword',{'zombie':kill('minecraft:zombie'),'skeleton':kill('minecraft:skeleton'),'spider':kill('minecraft:spider')},reward=R(('minecraft:arrow',12)))
obtain(g,'quiver','Carry Ammunition','Obtain a Sagittary quiver and check its tooltip for the controls.','sagittary:quiver',reward=R(('minecraft:arrow',16)))
obtain(g,'compound','A Ranged Upgrade','Obtain a compound bow. Check JEI for its recipe.','sagittary:compound_bow',parent='quiver',reward=R(('minecraft:flint',8)))
obtain(g,'enchant','Enchanting Table','Obtain an enchanting table. Books, lapis and experience support the next equipment tier.','minecraft:enchanting_table',reward=R(('minecraft:lapis_lazuli',12)))
add(g,'enchanted','First Enchantment','Enchant an item at an enchanting table. Bring lapis lazuli and enough experience.','minecraft:enchanted_book',{'enchant':{'trigger':'minecraft:enchanted_item'}},parent='enchant')
obtain(g,'diamond','Diamond Protection','Obtain a diamond chestplate before choosing your hardest expedition.','minecraft:diamond_chestplate',reward=R(('minecraft:golden_carrot',6)))
obtain(g,'anvil','Maintain Your Equipment','Obtain an anvil for repair, naming and book application.','minecraft:anvil',parent='enchant',reward=R(('minecraft:experience_bottle',4)))
# 8: broad expeditions; no compulsory exact rare structure search.
g='expeditions';root(g,'Overworld Expeditions','Explore at your own pace. Look for dungeons, taverns and landmarks, and bring back supplies for your next trip.','minecraft:filled_map')
obtain(g,'map','Take a Map','Obtain a filled map. Keep track of useful places; there are no required fixed coordinates.','minecraft:filled_map',reward=R(('minecraft:paper',8)))
add(g,'pyramid','Desert Pyramid','Enter a desert pyramid. Check for traps before collecting its loot.','minecraft:chiseled_sandstone',{'visit':visit('minecraft:desert_pyramid')},reward=R(('minecraft:torch',12)))
add(g,'shipwreck','A Shipwreck','Explore a shipwreck. Maps and ocean resources can start a longer trip.','minecraft:oak_boat',{'visit':visit('minecraft:shipwreck')},reward=R(('minecraft:cooked_cod',6)))
obtain(g,'heart','Buried Treasure','Obtain a heart of the sea. Keep it for a conduit or your final star.','minecraft:heart_of_the_sea',parent='shipwreck')
add(g,'monument','Ocean Monument','Enter an ocean monument. Plan breathing, visibility and an escape route.','minecraft:prismarine',{'visit':visit('minecraft:monument')},parent='shipwreck',reward=R(('minecraft:golden_carrot',6)))
add(g,'guardian','Elder Guardian','Defeat an elder guardian. Look for a Guardian Eye among the drops.','minecraft:prismarine_crystals',{'kill':kill('minecraft:elder_guardian')},parent='monument',challenge=True,reward=R(('minecraft:experience_bottle',6)))
add(g,'evoker','An Evoker','Defeat an evoker in a raid or woodland mansion. Either route counts.','minecraft:totem_of_undying',{'kill':kill('minecraft:evoker')},challenge=True,reward=R(('minecraft:golden_carrot',6)))
# 8: meaningful Nether preparation and outputs.
g='nether';root(g,'Nether Expeditions','Fortress and bastion trips supply brewing, equipment and eyes. A safe route home matters more than rushing the next marker.','minecraft:blaze_rod')
add(g,'enter','Into the Nether','Enter the Nether. Mark your portal before travelling far.','minecraft:obsidian',{'travel':dimension('minecraft:the_nether')},reward=R(('minecraft:gold_ingot',4)))
add(g,'fortress','Nether Fortress','Enter a Nether fortress. Watch for blazes and wither skeletons.','minecraft:nether_bricks',{'visit':visit('minecraft:fortress')},parent='enter',reward=R(('minecraft:golden_carrot',6)))
obtain(g,'rods','Brewing Materials','Obtain six blaze rods. Save some for powder and a brewing stand.','minecraft:blaze_rod',parent='fortress',count=6)
obtain(g,'brewing','Brewing Stand','Obtain a brewing stand and grow nether wart for a renewable supply.','minecraft:brewing_stand',parent='rods',reward=R(('minecraft:glass_bottle',12)))
add(g,'bastion','Bastion','Enter a bastion remnant. Bring gold armor and plan around piglin brutes.','minecraft:blackstone',{'visit':visit('minecraft:bastion_remnant')},parent='enter',challenge=True,reward=R(('minecraft:experience_bottle',6)))
obtain(g,'netherite','Ancient Debris to Netherite','Obtain a netherite ingot. Use it at a smithing table to improve your equipment.','minecraft:netherite_ingot',parent='bastion')
add(g,'wither','The Wither','Defeat the Wither. This is an optional eye route; its nether star also contributes to the final trophy.','minecraft:nether_star',{'kill':kill('minecraft:wither')},parent='fortress',challenge=True,reward=R(('minecraft:experience_bottle',8)))
# 18: native End Remastered eyes, then the final crafted trophy (requires dragon kill too).
g='end';root(g,'The End','Collect twelve DIFFERENT End Remastered eyes and use them in the stronghold portal. Four eye routes can be skipped. JEI shows the pack\'s deterministic recipes for Cryptic, Undead, Cold and Exotic Eyes. You can also find eyes through exploration, combat and trading.','endrem:old_eye')
eyes=[('old','Desert Pyramids','Search desert-pyramid loot.'),('nether','Fortresses','Search Nether-fortress loot.'),('cold','Cold Eye','Craft it from packed ice, tin and an Ender Pearl.'),('rogue','Jungle Pyramids','Search jungle-pyramid loot.'),('black','Buried Treasure','Search buried treasure chests.'),('magical','Evokers','Defeat evokers and look for a Magical Eye among the drops.'),('lost','Mineshafts','Explore mineshaft loot.'),('corrupted','Outposts','Investigate pillager-outpost loot.'),('wither','Wither Eye','Defeat the Wither and look for a Wither Eye among the drops.'),('guardian','Guardian Eye','Defeat elder guardians and look for a Guardian Eye among the drops.'),('witch','Witch Eye','Craft it using a Witch Pupil; check JEI for the recipe.'),('cursed','Bastions','Search bastion treasure.'),('exotic','Exotic Eye','Craft the pack recipe combining fishing catches, an aquatic shell and electrum.'),('evil','Trading','Trade with a master cleric and look for an Evil Eye.'),('undead','Undead Eye','Craft it from a wither skeleton skull, bone blocks, invar and an Ender Pearl.'),('cryptic','Cryptic Eye','Craft it from diamonds, lapis, a book, electrum and an Ender Pearl.')]
for eye,title,text in eyes:obtain(g,eye,title,text+' Obtain this eye; duplicates do not replace a different eye at the portal.',f'endrem:{eye}_eye',reward=R(('minecraft:experience_bottle',2)))
# A normal item with components provides a pack trophy without adding a gameplay mod.
final=add(g,'survival_star','Survival Star','Defeat the dragon and obtain the crafted Survival Star. Its recipe combines bronze, invar, electrum, a deluxe rod, a compound bow, a heart of the sea, a nether star and dragon\'s breath. Assemble it to finish the pack.','minecraft:nether_star',{'dragon':kill('minecraft:ender_dragon'),'star':{'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':'minecraft:nether_star','components':{'minecraft:custom_name':{'text':'Survival Star'},'minecraft:custom_data':{'codex_survival_finale':True}}}]}}},reward={'xp':100,'items':[('minecraft:firework_rocket',16)]},challenge=True,narrative='Most of this recipe is prepared before the End. Bring bottles to the dragon fight to collect its breath. Finish the star after the fight; the remaining eye routes and projects are optional.')
assert len(entries)==80,(len(entries),{g:sum(e['group']==g for e in entries) for g in {e['group'] for e in entries}})
# Native recipes; supported shaped/shapeless fields are editable through Codex's recipe GUI.
def shaped(id,pattern,key,result,count=1,components=None):
 output={'id':result,'count':count}
 if components:output['components']=components
 write(PACK/f'data/{id.split(":")[0]}/recipe/{id.split(":")[1]}.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':pattern,'key':key,'result':output})
shaped('codex_survival:cryptic_eye',['ELE','DBD','EPE'],{'E':'spelunkery:electrum_ingot','L':'minecraft:lapis_block','D':'minecraft:diamond','B':'minecraft:book','P':'minecraft:ender_pearl'},'endrem:cryptic_eye')
shaped('codex_survival:undead_eye',[' B ','ISI',' P '],{'B':'minecraft:bone_block','I':'spelunkery:invar_ingot','S':'minecraft:wither_skeleton_skull','P':'minecraft:ender_pearl'},'endrem:undead_eye')
shaped('codex_survival:cold_eye',['III','TPT','III'],{'I':'minecraft:packed_ice','T':'spelunkery:tin_ingot','P':'minecraft:ender_pearl'},'endrem:cold_eye')
# Replace the existing exotic recipe; the others above are additional deterministic routes.
shaped('endrem:exotic_eye',[' CE','SPS','EB '],{'C':'fishingfrenzy:carp','B':'fishingfrenzy:bream','S':'minecraft:nautilus_shell','P':'minecraft:ender_pearl','E':'spelunkery:electrum_ingot'},'endrem:exotic_eye')
# Identify exact native recipe identifiers instead of guessing their paths.
mods=ROOT/'runs/survival-pack/game/mods'
def recipe_for(modid,output):
 for jar in mods.glob('*.jar'):
  with zipfile.ZipFile(jar) as z:
   if 'fabric.mod.json' not in z.namelist() or json.loads(z.read('fabric.mod.json'))['id']!=modid:continue
   for name in z.namelist():
    if name.startswith('data/') and '/recipe/' in name and name.endswith('.json'):
     d=json.loads(z.read(name));r=d.get('result',{})
     if isinstance(r,dict) and r.get('id')==output:
      bits=name.split('/');return bits[1]+':'+ '/'.join(bits[3:])[:-5]
 raise RuntimeError('Native recipe missing: '+output)
shaped(recipe_for('backpacked','backpacked:backpack'),['LCL','LBL','LLL'],{'L':'minecraft:leather','C':'farmersdelight:canvas','B':'spelunkery:bronze_ingot'},'backpacked:backpack')
shaped(recipe_for('fishingfrenzy','fishingfrenzy:deluxe_fishing_rod'),['  B',' BS','B S'],{'B':'spelunkery:bronze_ingot','S':'minecraft:string'},'fishingfrenzy:deluxe_fishing_rod')
shaped('codex_survival:survival_star',['BIE','RSC','HDT'],{'B':'spelunkery:bronze_block','I':'spelunkery:invar_block','E':'spelunkery:electrum_block','R':'fishingfrenzy:deluxe_fishing_rod','S':'minecraft:nether_star','C':'sagittary:compound_bow','H':'minecraft:heart_of_the_sea','D':'minecraft:dragon_breath','T':'minecraft:diamond_block'},'minecraft:nether_star',components={'minecraft:custom_name':{'text':'Survival Star'},'minecraft:lore':[{'text':'Codex Survival — completed'}],'minecraft:custom_data':{'codex_survival_finale':True}})
write(PACK/'pack.mcmeta',{'pack':{'description':'Codex Survival playtest —80 quests, Fabric26.1.2','min_format':[101,1],'max_format':[101,1]}})
for name,data in [('quests',quests),('claimable_rewards',claims),('submission_tasks',submissions),('statistic_tasks',stats),('layout',{'positions':positions})]:write(PACK/f'codex/{name}.json',data)
# Recipe-book discovery for edited/new recipes, separate from real crafting restrictions.
for file in PACK.glob('data/*/recipe/**/*.json'):
 namespace=file.parts[file.parts.index('data')+1];relative=file.relative_to(PACK/f'data/{namespace}/recipe').as_posix()[:-5];rid=namespace+':'+relative
 write(PACK/f'data/{NS}/advancement/recipes/{namespace}/{relative}.json',{'criteria':{'arrive':{'trigger':'minecraft:tick'}},'rewards':{'recipes':[rid]}})
write(OUT/'config/codex-examples.json',{g:False for g in ['vanilla','showcase','sagittary','sleepcycle']})
write(OUT/'config/codex-client.json',{'scale':0,'hud':True,'animations':True,'hold_accept':True,'notices':True,'sounds':True,'hud_position':0,'pins':[]})
(OUT/'options.txt').write_text('guiScale:3\nrenderDistance:10\nsimulationDistance:6\nmaxFps:120\ntutorialStep:none\n',encoding='utf8')
write(ROOT/'modpacks/codex-survival/quests.index.json',entries)
lines=['# Codex Survival — quest index','', '80 quests. Native recipe-book unlock advancements are excluded from this count.','']
for group in dict.fromkeys(e['group'] for e in entries):
 lines+=['## '+group,'']
 for e in entries:
  if e['group']==group:lines+=['- **'+e['title']+'** — '+e['description']]
 lines+=['']
(ROOT/'modpacks/codex-survival/QUESTS.md').write_text('\n'.join(lines),encoding='utf8')
print('Built',len(entries),'quests in8 sections and7 custom/overridden recipes',flush=True)
