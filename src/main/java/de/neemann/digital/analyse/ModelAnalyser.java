/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.analyse.expression.BitSetter;
import de.neemann.digital.analyse.quinemc.BoolTableByteArray;
import de.neemann.digital.core.*;
import de.neemann.digital.core.flipflops.FlipflopD;
import de.neemann.digital.core.switching.NFET;
import de.neemann.digital.core.switching.Relay;
import de.neemann.digital.core.switching.RelayDT;
import de.neemann.digital.core.wiring.Clock;
import de.neemann.digital.core.wiring.Splitter;
import de.neemann.digital.draw.elements.PinException;
import de.neemann.digital.gui.Main;
import de.neemann.digital.lang.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyses a given model.
 * Calculates the truth table which is generated by the given model
 */
public class ModelAnalyser {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelAnalyser.class);
    private static final int MAX_INPUTS_ALLOWED = 24;

    private final Model model;
    private final ArrayList<Signal> inputs;
    private final ArrayList<Signal> outputs;
    private ModelAnalyserInfo modelAnalyzerInfo;

    /**
     * Creates a new instance
     *
     * @param model the model
     * @throws AnalyseException AnalyseException
     */
    public ModelAnalyser(Model model) throws AnalyseException {
        this.model = model;

        modelAnalyzerInfo = new ModelAnalyserInfo(model);

        inputs = checkBinaryInputs(model.getInputs());
        checkUnique(inputs);
        outputs = checkBinaryOutputs(model.getOutputs());

        modelAnalyzerInfo.setInOut(inputs, outputs);

        for (Node n : model)
            if (n.hasState() && !(n instanceof FlipflopD))
                throw new AnalyseException(Lang.get("err_cannotAnalyse_N", n.getClass().getSimpleName()));

        int i = 0;
        List<FlipflopD> flipflops = model.findNode(FlipflopD.class);
        flipflops = replaceMultiBitFlipflops(flipflops);
        for (FlipflopD ff : flipflops) {
            checkClock(ff);
            if (ff.getDataBits() != 1)
                throw new AnalyseException(Lang.get("err_MultiBitFlipFlopFound"));

            ff.getDInput().removeObserver(ff); // turn off flipflop
            String label = getUniqueNameFor(ff);

            outputs.add(i++, new Signal(addOne(label), ff.getDInput()));

            modelAnalyzerInfo.setSequentialInitValue(label, ff.getDefault());

            ObservableValue q = ff.getOutputs().get(0);
            final Signal sig = new Signal(label, q);
            if (inputs.contains(sig))
                throw new AnalyseException(Lang.get("err_varName_N_UsedTwice", sig.getName()));
            inputs.add(sig);

            ObservableValue notQ = ff.getOutputs().get(1);
            q.addObserver(new NodeWithoutDelay(notQ) {
                @Override
                public void hasChanged() {
                    notQ.setValue(~q.getValue());
                }
            });
        }

        if (inputs.size() == 0)
            throw new AnalyseException(Lang.get("err_analyseNoInputs"));
        if (outputs.size() == 0)
            throw new AnalyseException(Lang.get("err_analyseNoOutputs"));
    }

    /**
     * Adds the "+1" to the variables name
     *
     * @param name the vars name
     * @return the modified name
     */
    public static String addOne(String name) {
        if (name.endsWith("^n"))
            return name.substring(0, name.length() - 1) + "{n+1}";
        else
            return name + "+1";
    }


    private String getUniqueNameFor(FlipflopD ff) {
        String label = ff.getLabel();
        if (label.length() == 0)
            label = createOutputBasedName(ff);

        if (!label.endsWith("n"))
            label += "^n";

        return new LabelNumbering(label).create(this::inputExist);
    }

    private boolean inputExist(String label) {
        for (Signal i : inputs)
            if (i.getName().equals(label))
                return true;
        return false;
    }

    private String createOutputBasedName(FlipflopD ff) {
        ObservableValue q = ff.getOutputs().get(0);
        for (Signal o : outputs) {
            if (o.getValue() == q)
                return o.getName();
        }

        return "Z";
    }

    private void checkUnique(ArrayList<Signal> signals) throws AnalyseException {
        for (int i = 0; i < signals.size() - 1; i++)
            for (int j = i + 1; j < signals.size(); j++)
                if (signals.get(i).equals(signals.get(j)))
                    throw new AnalyseException(Lang.get("err_varName_N_UsedTwice", signals.get(i).getName()));
    }

    private ArrayList<Signal> checkBinaryOutputs(ArrayList<Signal> list) throws AnalyseException {
        ArrayList<Signal> outputs = new ArrayList<>();
        for (Signal s : list) {
            final int bits = s.getValue().getBits();
            if (bits == 1)
                outputs.add(s);
            else {
                try {
                    Splitter sp = Splitter.createOneToN(bits);
                    sp.setInputs(s.getValue().asList());
                    SplitPinString pins = SplitPinString.create(s);

                    final ObservableValues spOutputs = sp.getOutputs();
                    for (int i = spOutputs.size() - 1; i >= 0; i--)
                        outputs.add(new Signal(s.getName() + i, spOutputs.get(i)).setPinNumber(pins.getPin(i)));

                    s.getValue().fireHasChanged();

                    ArrayList<String> names = new ArrayList<>(bits);
                    for (int i = 0; i < bits; i++)
                        names.add(s.getName() + i);

                    modelAnalyzerInfo.addOutputBus(s.getName(), names);

                } catch (NodeException e) {
                    throw new AnalyseException(e);
                }
            }
        }
        return outputs;
    }

    private ArrayList<Signal> checkBinaryInputs(ArrayList<Signal> list) throws AnalyseException {
        ArrayList<Signal> inputs = new ArrayList<>();
        for (Signal s : list) {
            final int bits = s.getValue().getBits();
            if (bits == 1)
                inputs.add(s);
            else {
                try {
                    Splitter sp = Splitter.createNToOne(bits);
                    final ObservableValue out = sp.getOutputs().get(0);
                    out.addObserver(new NodeWithoutDelay(s.getValue()) {
                        @Override
                        public void hasChanged() {
                            s.getValue().setValue(out.getValue());
                        }
                    });
                    out.fireHasChanged();

                    SplitPinString pins = SplitPinString.create(s);
                    ObservableValues.Builder builder = new ObservableValues.Builder();
                    for (int i = bits - 1; i >= 0; i--) {
                        ObservableValue o = new ObservableValue(s.getName() + i, 1);
                        builder.add(o);
                        inputs.add(new Signal(s.getName() + i, o).setPinNumber(pins.getPin(i)));
                    }
                    final ObservableValues inputsList = builder.reverse().build();
                    sp.setInputs(inputsList);

                    modelAnalyzerInfo.addInputBus(s.getName(), inputsList.getNames());

                } catch (NodeException e) {
                    throw new AnalyseException(e);
                }
            }
        }
        return inputs;
    }

    private void checkClock(Node node) throws AnalyseException {
        if (!getClock().hasObserver(node))
            throw new AnalyseException(Lang.get("err_ffNeedsToBeConnectedToClock"));
    }

    private ObservableValue getClock() throws AnalyseException {
        ArrayList<Clock> clocks = model.getClocks();
        if (clocks.size() != 1)
            throw new AnalyseException(Lang.get("err_aSingleClockNecessary"));
        return clocks.get(0).getClockOutput();
    }

    private List<FlipflopD> replaceMultiBitFlipflops(List<FlipflopD> flipflops) throws AnalyseException {
        ArrayList<FlipflopD> out = new ArrayList<>();
        for (FlipflopD ff : flipflops) {
            if (ff.getDataBits() == 1)
                out.add(ff);
            else {
                try {
                    model.removeNode(ff);
                    ff.getDInput().removeObserver(ff);
                    ff.getClock().removeObserver(ff);

                    Splitter insp = Splitter.createOneToN(ff.getDataBits());
                    insp.setInputs(new ObservableValues(ff.getDInput()));
                    ff.getDInput().fireHasChanged();

                    Splitter outsp = Splitter.createNToOne(ff.getDataBits());

                    ObservableValues.Builder spinput = new ObservableValues.Builder();
                    String label = ff.getLabel();
                    if (label.length() == 0)
                        label = createOutputBasedName(ff);
                    long def = ff.getDefault();
                    for (int i = ff.getDataBits() - 1; i >= 0; i--) {
                        ObservableValue qn = new ObservableValue("", 1);
                        ObservableValue nqn = new ObservableValue("", 1);
                        FlipflopD newff = new FlipflopD(label + i, qn, nqn, (def & (1L << i)) != 0 ? 1 : 0);
                        spinput.addAtTop(qn);
                        model.add(newff);
                        newff.setInputs(new ObservableValues(insp.getOutputs().get(i), getClock()));
                        out.add(newff);
                    }
                    outsp.setInputs(spinput.build());
                    for (ObservableValue v : spinput)
                        v.fireHasChanged();

                    final ObservableValue qout = ff.getOutputs().get(0);
                    final ObservableValue nqout = ff.getOutputs().get(1);
                    ObservableValue spq = outsp.getOutputs().get(0);
                    spq.addObserver(new NodeWithoutDelay(qout, nqout) {
                        @Override
                        public void hasChanged() {
                            final long value = spq.getValue();
                            qout.setValue(value);
                            nqout.setValue(~value);
                        }
                    });
                    spq.fireHasChanged();

                } catch (NodeException e) {
                    throw new AnalyseException(e);
                }
            }
        }
        return out;
    }

    /**
     * @return the models inputs
     */
    public ArrayList<Signal> getInputs() {
        return inputs;
    }

    /**
     * @return the models outputs
     */
    public ArrayList<Signal> getOutputs() {
        return outputs;
    }

    /**
     * Analyses the circuit
     *
     * @return the generated truth table
     * @throws NodeException      NodeException
     * @throws PinException       PinException
     * @throws BacktrackException BacktrackException
     * @throws AnalyseException   AnalyseException
     */
    public TruthTable analyse() throws NodeException, PinException, BacktrackException, AnalyseException {
        LOGGER.debug("start to analyse the model...");

        TruthTable tt = new TruthTable();
        tt.setModelAnalyzerInfo(getModelAnalyzerInfo());
        for (Signal s : inputs)
            tt.addVariable(s.getName());

        if (!Main.isExperimentalMode() && !modelContainsSwitches())
            CycleDetector.checkForCycles(inputs);

        DependencyAnalyser da = new DependencyAnalyser(this);
        long steps = da.getRequiredSteps(this);

        long tableRows = 1L << inputs.size();
        LOGGER.debug("analyse speedup: " + tableRows + " rows vs " + steps + " steps, speedup " + ((double) tableRows) / steps);

        long time = System.currentTimeMillis();


        if (tableRows <= steps || tableRows <= 128)
            simpleFiller(tt);
        else
            dependantFiller(tt, da);

        time = System.currentTimeMillis() - time;
        LOGGER.debug("model analysis: " + time / 1000.0 + " sec");

        return tt;
    }

    private boolean modelContainsSwitches() {
        for (Node n : model)
            if (n instanceof Relay
                    || n instanceof RelayDT
                    || n instanceof NFET) return true;
        return false;
    }

    private void simpleFiller(TruthTable tt) throws NodeException, AnalyseException {
        if (inputs.size() > MAX_INPUTS_ALLOWED)
            throw new AnalyseException(Lang.get("err_toManyInputs_max_N0_is_N1", MAX_INPUTS_ALLOWED, inputs.size()));


        BitSetter bitsetter = new BitSetter(inputs.size()) {
            @Override
            public void setBit(int row, int bit, boolean value) {
                inputs.get(bit).getValue().setBool(value);
            }
        };

        int rows = 1 << inputs.size();
        ArrayList<BoolTableByteArray> data = new ArrayList<>();
        for (Signal s : outputs) {
            BoolTableByteArray e = new BoolTableByteArray(rows);
            data.add(e);
            tt.addResult(s.getName(), e);
        }

        model.init();
        for (int row = 0; row < rows; row++) {
            bitsetter.fill(row);
            model.doStep();
            for (int i = 0; i < outputs.size(); i++) {
                data.get(i).set(row, outputs.get(i).getValue().getBool());
            }
        }
    }

    private void dependantFiller(TruthTable tt, DependencyAnalyser da) throws NodeException, AnalyseException {
        model.init();
        for (Signal out : outputs) {

            ArrayList<Signal> ins = reorder(da.getInputs(out), inputs);
            if (ins.size() > MAX_INPUTS_ALLOWED)
                throw new AnalyseException(Lang.get("err_toManyInputs_max_N0_is_N1", MAX_INPUTS_ALLOWED, ins.size()));

            int rows = 1 << ins.size();
            BoolTableByteArray e = new BoolTableByteArray(rows);
            BitSetter bitsetter = new BitSetter(ins.size()) {
                @Override
                public void setBit(int row, int bit, boolean value) {
                    ins.get(bit).getValue().setBool(value);
                }
            };

            for (int row = 0; row < rows; row++) {
                bitsetter.fill(row);
                model.doStep();
                e.set(row, out.getValue().getBool());
            }

            tt.addResult(out.getName(), new BoolTableExpanded(e, ins, inputs));
        }
    }

    private ModelAnalyserInfo getModelAnalyzerInfo() {
        return modelAnalyzerInfo;
    }

    private ArrayList<Signal> reorder(ArrayList<Signal> ins, ArrayList<Signal> originalOrder) {
        ArrayList<Signal> newList = new ArrayList<>();
        for (Signal i : originalOrder)
            if (ins.contains(i))
                newList.add(i);
        return newList;
    }

}
