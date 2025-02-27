package joshua.ui.tree_visualizer.browser;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JList;

import joshua.ui.tree_visualizer.DerivationTree;
import joshua.ui.tree_visualizer.DerivationViewer;
import joshua.ui.tree_visualizer.tree.Tree;

/**
 * A frame that displays a derivation tree.
 * 
 * @author jonny
 * 
 */
class DerivationTreeFrame extends JFrame {
  /**
   * Eclipse seems to think serialVersionUID is important. I don't know why.
   */
  private static final long serialVersionUID = -3173826443907629130L;

  /**
   * A button to move to the next source-side sentence in the file.
   */
  JButton nextSource;
  /**
   * A button to move to the previous source-side sentence in the file.
   */
  JButton previousSource;

  /**
   * A button to show or hide extra information about the derivation.
   */
  private JButton informationButton;

  /**
   * A panel holding the extra information about the derivation.
   */
  private JPanel informationPanel;

  /**
   * A label holding the current source sentence.
   */
  private JLabel sourceLabel;

  /**
   * A label holding the reference translation of the current source sentence.
   */
  private JLabel referenceLabel;

  /**
   * A label holding the one-best translation of the current source sentence.
   */
  private JLabel oneBestLabel;

  /**
   * A panel that holds the buttons, as well as labels to show which derivation is currently being
   * displayed.
   */
  private JPanel controlPanel;
  /**
   * A panel used to display the derivation tree itself.
   */
  private JPanel viewPanel;

  /**
   * This component displays the derivation tree's JUNG graph.
   */
  private DerivationViewer dv;

  /**
   * Index to determine which data set (which n-best file) this frame brings its graphs from.
   */
  private final int dataSetIndex;

  private static final int DEFAULT_WIDTH = 640;
  private static final int DEFAULT_HEIGHT = 480;

  /**
   * Color to use to render target-side trees.
   */
  private Color targetColor;

	private JList mainList;

  /**
   * The default constructor.
   */
  public DerivationTreeFrame(int index, JList mainList) {
    super("Joshua Derivation Tree");
		this.mainList = mainList;
    setLayout(new BorderLayout());
    setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    controlPanel = new JPanel(new BorderLayout());
    informationPanel = new JPanel(new GridLayout(3, 1));

    sourceLabel = new JLabel("source sentence");
    referenceLabel = new JLabel("reference translation");
    oneBestLabel = new JLabel("one best translation");

    informationPanel.add(sourceLabel);
    informationPanel.add(referenceLabel);
    informationPanel.add(oneBestLabel);
    informationPanel.setVisible(false);

    controlPanel.add(informationPanel, BorderLayout.SOUTH);

    initializeButtons();
    layoutControl();

    viewPanel = new JPanel(new BorderLayout());
    dv = null;

    dataSetIndex = index;
    targetColor = Browser.dataSetColors[dataSetIndex % Browser.dataSetColors.length];

    getContentPane().add(viewPanel, BorderLayout.CENTER);
    getContentPane().add(controlPanel, BorderLayout.SOUTH);
    // drawGraph();
    setVisible(true);
  }

  /**
   * Lays out the control buttons of this frame.
   */
  private void layoutControl() {
    /*
     * JPanel ctlLeft = new JPanel(new GridLayout(2, 1)); JPanel ctlCenter = new JPanel(new
     * GridLayout(2, 1)); JPanel ctlRight = new JPanel(new GridLayout(2, 1));
     * 
     * controlPanel.add(ctlLeft, BorderLayout.WEST); controlPanel.add(ctlCenter,
     * BorderLayout.CENTER); controlPanel.add(ctlRight, BorderLayout.EAST);
     * 
     * ctlLeft.add(previousSource); ctlRight.add(nextSource);
     */

    controlPanel.add(previousSource, BorderLayout.WEST);
    controlPanel.add(nextSource, BorderLayout.EAST);
    controlPanel.add(informationButton, BorderLayout.CENTER);
    return;
  }

  /**
   * Initializes the control buttons of this frame.
   */
  private void initializeButtons() {
    nextSource = new JButton(">");
    previousSource = new JButton("<");
    informationButton = new JButton("More Information");

    nextSource.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        int index = mainList.getSelectedIndex();
				mainList.setSelectedIndex(index + 1);
        return;
      }
    });
    previousSource.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        int index = mainList.getSelectedIndex();
				if (index > 0) {
					mainList.setSelectedIndex(index - 1);
				}
        return;
      }
    });
    informationButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        JButton source = (JButton) e.getSource();
        if (informationPanel.isVisible()) {
          source.setText("More Information");
          informationPanel.setVisible(false);
        } else {
          source.setText("Less Information");
          informationPanel.setVisible(true);
        }
        return;
      }
    });
    return;
  }

  /**
   * Displays the derivation tree for the current candidate translation. The current candidate
   * translation is whichever translation is currently highlighted in the Derivation Browser's
   * chooser frame.
   */
  public void drawGraph(TranslationInfo ti) {
    viewPanel.removeAll();
    String src = ti.sourceSentence();
    Tree tgt = ti.translations().get(dataSetIndex);
		String ref = ti.reference();

    sourceLabel.setText(src);
    referenceLabel.setText(ref);
    oneBestLabel.setText(tgt.yield());

    DerivationTree tree = new DerivationTree(tgt, src);
    if (dv == null) {
      dv =
          new DerivationViewer(tree, viewPanel.getSize(), targetColor,
              DerivationViewer.AnchorType.ANCHOR_LEFTMOST_LEAF);
    } else {
      dv.setGraph(tree);
    }
    viewPanel.add(dv, BorderLayout.CENTER);
    dv.revalidate();
    repaint();
    getContentPane().repaint();
    return;
  }

  /**
   * Makes this frame unmodifiable, so that the tree it displays cannot be changed. In fact, all
   * that happens is the title is update and the navigation buttons are disabled. This method is
   * intended to prevent the user from modifying the frame, not to prevent other code from modifying
   * it.
   */
  public void disableNavigationButtons() {
    setTitle(getTitle() + " (fixed)");
    nextSource.setEnabled(false);
    previousSource.setEnabled(false);
    return;
  }
}
