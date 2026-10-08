import matplotlib as mpl
mpl.use("Agg")
import matplotlib.pyplot as plt
C = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
MARK = ["o", "s", "^", "D", "v"]
def setup():
    mpl.rcParams.update({
        "font.family": "serif", "font.serif": ["DejaVu Serif"], "font.size": 8.5, "axes.labelsize": 8.5,
        "xtick.labelsize": 7.5, "ytick.labelsize": 7.5, "legend.fontsize": 7.5, "axes.edgecolor": INK2,
        "axes.labelcolor": INK, "xtick.color": INK2, "ytick.color": INK2, "axes.spines.top": False,
        "axes.spines.right": False, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6,
        "axes.axisbelow": True, "legend.frameon": False, "lines.linewidth": 1.4, "lines.markersize": 4.5,
        "pdf.fonttype": 42, "savefig.bbox": "tight", "savefig.pad_inches": 0.02})
