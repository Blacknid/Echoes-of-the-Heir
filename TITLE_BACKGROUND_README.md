# How to Get Background Images for Your Game

## 🎨 Where to Find Free Background Images

### **Recommended Websites:**

#### **1. OpenGameArt.org** (Best for Game Assets)
- **URL**: https://opengameart.org/
- **Why**: Free game assets, many fantasy/scifi backgrounds
- **Search for**: "background", "sky", "landscape", "fantasy background"
- **License**: Most are CC0/Public Domain

#### **2. Itch.io Asset Packs**
- **URL**: https://itch.io/game-assets/free
- **Search for**: "background pack", "skybox", "environment"
- **Filter**: Free assets only

#### **3. Pixabay.com** (General Images)
- **URL**: https://pixabay.com/
- **Search for**: "fantasy landscape", "medieval background", "game background"
- **License**: Free for commercial use

#### **4. Unsplash.com** (High Quality)
- **URL**: https://unsplash.com/
- **Search for**: "fantasy", "forest", "castle", "mountains"

#### **5. Game Dev Marketplaces**
- **URL**: https://kenney.nl/assets (free assets)
- **URL**: https://freeassets.gamedevmarket.net/

## 🎯 What Makes a Good Title Screen Background?

### **Visual Requirements:**
- **Resolution**: At least 1920x1080 (will be scaled down)
- **Style**: Fantasy, medieval, or match your game theme
- **Colors**: Not too bright (text needs to be readable)

### **Content Ideas:**
- 🌌 **Starry night sky** with mountains
- 🏰 **Medieval castle** landscape
- 🌲 **Enchanted forest** with mist
- 🏔️ **Mountain range** at sunset
- 🗡️ **Fantasy scene** with floating islands

## 📝 How to Add Your Background

1. **Download** an image you like (PNG format preferred)
2. **Rename** it to `background.png`
3. **Place** it in: `ceva/src/res/background.png`
4. **Restart** your game

## 🎨 Title Screen Color & Font Recommendations

### **Current Setup:**
- Title: White text with gray shadow
- Menu: White text
- Selector: White ">" arrow

### **Fantasy Theme Suggestions:**

#### **🌟 Title Text Colors:**
- **Gold/Yellow**: `new Color(255, 215, 0)` or `new Color(218, 165, 32)`
- **Deep Red**: `new Color(139, 0, 0)` or `new Color(178, 34, 52)`
- **Royal Purple**: `new Color(75, 0, 130)` or `new Color(138, 43, 226)`
- **Ancient Bronze**: `new Color(205, 127, 50)`

#### **📜 Menu Text Colors:**
- **Soft Gold**: `new Color(255, 223, 128)`
- **Silver**: `new Color(192, 192, 192)`
- **Cream**: `new Color(255, 253, 208)`
- **Light Blue**: `new Color(173, 216, 230)`

#### **🏹 Selector Colors:**
- **Bright Gold**: `new Color(255, 215, 0)`
- **Electric Blue**: `new Color(0, 191, 255)`
- **Emerald Green**: `new Color(0, 128, 0)`

### **✨ Font Suggestions:**
- **Fantasy Fonts**: "Papyrus", "Old English Text MT", "Algerian"
- **Clean Fonts**: "Cambria", "Georgia", "Times New Roman"
- **Bold Impact**: Keep current bold styling for titles

### **💡 Pro Tips:**
- Test readability over your background
- Use shadows for better contrast
- Keep menu text slightly smaller than title
- Consider subtle glow effects for magical feel

## 🔧 Background Opacity Control

### **Current Setting:** 70% opacity (reduces brightness)

### **How to Adjust Opacity:**

Edit the `drawTitleScreen()` method in `UI.java`:

```java
// Change 0.7f to your desired opacity (0.0f = invisible, 1.0f = full brightness)
g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
```

### **Recommended Opacity Levels:**
- **0.9f** - Subtle dimming (barely noticeable)
- **0.7f** - Moderate brightness reduction (current setting)
- **0.5f** - Significant dimming (good for very bright images)
- **0.3f** - Heavy dimming (for extremely bright backgrounds)

### **Why Opacity Helps:**
- ✅ **Better text readability** over bright backgrounds
- ✅ **Professional look** with subtle depth
- ✅ **Atmospheric effect** for immersion
- ✅ **Easy to adjust** - just change one number

### **Testing Tip:**
Restart your game after changing the opacity value to see the difference immediately!

## 🚀 Quick Start

1. Go to https://opengameart.org/
2. Search "fantasy background"
3. Download a PNG you like
4. Rename to `background.png`
5. Put in `ceva/src/res/`
6. Run your game!

Your title screen will instantly look much more professional! 🎮