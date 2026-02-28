# 🎨 Title Screen Color Schemes for Michiduta Adventure

## 🌟 Current Fantasy Theme (Applied)

### **Title Text:**
- **Main**: Gold (`new Color(255, 215, 0)`)
- **Shadow**: Dark Gray (`new Color(50, 50, 50)`)

### **Menu Options:**
- **Text**: Soft Gold (`new Color(255, 223, 128)`)
- **Selector**: Bright Gold (`new Color(255, 215, 0)`)

### **Info Text:**
- **Color**: Silver (`new Color(192, 192, 192)`)

## 🎨 Alternative Color Schemes

### **🔥 Dark Fantasy Theme:**
```java
// Title: Deep Red
g2.setColor(new Color(139, 0, 0)); // Dark red
// Menu: Blood red
g2.setColor(new Color(178, 34, 52)); // Firebrick
// Selector: Bright red
g2.setColor(new Color(255, 0, 0)); // Red
```

### **🌙 Mystic Theme:**
```java
// Title: Royal Purple
g2.setColor(new Color(138, 43, 226)); // Blue violet
// Menu: Light purple
g2.setColor(new Color(221, 160, 221)); // Plum
// Selector: Electric purple
g2.setColor(new Color(186, 85, 211)); // Medium orchid
```

### **⚔️ Medieval Theme:**
```java
// Title: Bronze
g2.setColor(new Color(205, 127, 50)); // Peru
// Menu: Copper
g2.setColor(new Color(184, 115, 51)); // Dark goldenrod
// Selector: Gold
g2.setColor(new Color(255, 215, 0)); // Gold
```

### **❄️ Ice Theme:**
```java
// Title: Ice blue
g2.setColor(new Color(173, 216, 230)); // Light blue
// Menu: Frost
g2.setColor(new Color(176, 224, 230)); // Powder blue
// Selector: Electric blue
g2.setColor(new Color(0, 191, 255)); // Deep sky blue
```

## 📝 How to Change Colors

Edit the `drawTitleScreen()` method in `UI.java`:

```java
// Change title color
g2.setColor(new Color(R, G, B)); // Replace R,G,B with 0-255 values

// Change menu colors
g2.setColor(new Color(255, 223, 128)); // Current soft gold

// Change selector color
g2.setColor(new Color(255, 215, 0)); // Current bright gold
```

## 🎯 Color Picker Tools

- **Online**: https://www.google.com/search?q=color+picker
- **Built-in**: Most image editors have color pickers
- **Hex to RGB**: Search "hex to rgb converter"

## 💡 Tips for Your Game

- **Test readability** over your background image
- **Adjust opacity** if text is hard to read (currently 70%)
- **Consider contrast** - dark backgrounds need light text
- **Keep it thematic** - fantasy colors for fantasy game
- **Subtle variations** - don't use too many different colors
- **Accessibility** - ensure good contrast for all players

## 🚀 Current Setup Summary

Your title screen now uses a **golden fantasy theme** that perfectly matches "Michiduta Adventure"! The gold title text with soft gold menu options creates an epic, medieval fantasy feel. 🎮✨