package name.abuchen.portfolio.ui.views.dashboard;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.swtchart.Chart;
import org.swtchart.IAxis;
import org.swtchart.IAxis.Position;
import org.swtchart.IBarSeries;
import org.swtchart.ICustomPaintListener;
import org.swtchart.IPlotArea;
import org.swtchart.ISeries;
import org.swtchart.ISeries.SeriesType;
import org.swtchart.LineStyle;

import name.abuchen.portfolio.model.Dashboard;
import name.abuchen.portfolio.model.Dashboard.Widget;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.CacheKey;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.chart.PlainChart;
import name.abuchen.portfolio.ui.util.chart.TimelineChartToolTip;
import name.abuchen.portfolio.ui.util.format.AmountNumberFormat;
import name.abuchen.portfolio.ui.util.format.ThousandsNumberFormat;
import name.abuchen.portfolio.util.Interval;
import name.abuchen.portfolio.util.Pair;
import name.abuchen.portfolio.util.TextUtil;

public class ActivityWidget extends WidgetDelegate<List<TransactionPair<?>>>
{
    public enum ChartType
    {
        COUNT(Messages.ColumnCount), //
        SUM(Messages.ColumnSum), //
        COUNT_BY_YEAR(Messages.ColumnCountByYear), //
        SUM_BY_YEAR(Messages.ColumnSumByYear);

        private String label;

        private ChartType(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    static class ChartTypeConfig extends EnumBasedConfig<ChartType>
    {
        public ChartTypeConfig(WidgetDelegate<?> delegate)
        {
            super(delegate, Messages.LabelChartType, ChartType.class, Dashboard.Config.AGGREGATION, Policy.EXACTLY_ONE);
        }
    }

    public static class TimeGridPaintListener implements ICustomPaintListener
    {
        private static final int INDENT = 5;

        private final Chart chart;

        public TimeGridPaintListener(Chart chart)
        {
            this.chart = chart;
        }

        @Override
        public void paintControl(PaintEvent e)
        {
            if (chart.getData() instanceof List && !((List<?>) chart.getData()).isEmpty()
                            && (((List<?>) chart.getData()).get(0) instanceof Year))
            {
                // in case that the chart is grouped by year we do not draw the
                // markers per year
                return;
            }

            @SuppressWarnings("unchecked")
            List<YearMonth> yearMonths = (List<YearMonth>) chart.getData();
            if (yearMonths == null || yearMonths.isEmpty())
                return;

            // collect years

            IAxis xAxis = chart.getAxisSet().getXAxis(0);

            List<Pair<Integer, Integer>> years = new ArrayList<>();
            years.add(new Pair<>(yearMonths.get(0).getYear(), xAxis.getPixelCoordinate(0)));
            for (int index = 1; index < yearMonths.size(); index++)
            {
                YearMonth yearMonth = yearMonths.get(index);
                if (yearMonth.getYear() == years.get(years.size() - 1).getKey())
                    continue;
                years.add(new Pair<>(yearMonths.get(index).getYear(), xAxis.getPixelCoordinate(index)));
            }

            // draw marker per year

            for (int index = 0; index < years.size(); index++)
            {
                Pair<Integer, Integer> pair = years.get(index);
                int x = pair.getValue();

                e.gc.drawLine(x, 0, x, e.height);

                int nextX = index + 1 < years.size() ? years.get(index + 1).getValue() - INDENT : e.width - INDENT;
                int availableWidth = nextX - x;

                String label = String.valueOf(pair.getKey());
                if (drawLabel(e.gc, label, x, availableWidth))
                    continue;

                label = String.format("%02d", pair.getKey() % 100); //$NON-NLS-1$
                drawLabel(e.gc, label, x, availableWidth);
            }
        }

        private boolean drawLabel(GC gc, String label, int x, int availableWidth)
        {
            Point point = gc.textExtent(label);
            if (point.x < availableWidth)
            {
                gc.drawText(label, x + INDENT, INDENT, true);
                return true;
            }
            else
            {
                return false;
            }
        }

        @Override
        public boolean drawBehindSeries()
        {
            return true;
        }
    }

    private Label title;
    private Chart chart;
    private TimelineChartToolTip toolTip;

    private CurrencyConverter converter;

    public ActivityWidget(Widget widget, DashboardData data)
    {
        super(widget, data);

        addConfig(new ChartShowYAxisConfig(this, true));
        addConfig(new ReportingPeriodConfig(this));
        addConfig(new ClientFilterConfig(this));
        addConfig(new ChartTypeConfig(this));
        addConfig(new ChartHeightConfig(this));

        this.converter = data.getCurrencyConverter();
    }

    @Override
    public Composite createControl(Composite parent, DashboardResources resources)
    {
        Composite container = new Composite(parent, SWT.NONE);
        container.setData(UIConstants.CSS.CLASS_NAME, this.getContainerCssClassNames());
        GridLayoutFactory.fillDefaults().numColumns(1).margins(5, 5).applyTo(container);
        container.setBackground(parent.getBackground());

        title = new Label(container, SWT.NONE);
        title.setBackground(container.getBackground());
        title.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.TITLE);
        title.setText(TextUtil.tooltip(getWidget().getLabel()));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(title);

        chart = new PlainChart(container, SWT.NONE);
        chart.setData(UIConstants.CSS.CLASS_NAME, "chart"); //$NON-NLS-1$
        getDashboardData().getStylingEngine().style(chart);

        chart.setBackground(container.getBackground());
        chart.getTitle().setVisible(false);
        chart.getTitle().setText(title.getText());

        chart.getLegend().setVisible(false);

        toolTip = new TimelineChartToolTip(chart);
        toolTip.enableCategory(true);
        toolTip.reverseLabels(true);
        toolTip.setDefaultValueFormat(new DecimalFormat("#")); //$NON-NLS-1$
        toolTip.setXAxisFormat(obj -> {
            Integer index = (Integer) obj;
            List<?> yearsOrYearMonths = (List<?>) chart.getData();

            return yearsOrYearMonths.get(index).toString();
        });

        int yHint = get(ChartHeightConfig.class).getPixel();
        GridDataFactory.fillDefaults().hint(SWT.DEFAULT, yHint).grab(true, false).applyTo(chart);

        // configure axis

        IAxis xAxis = chart.getAxisSet().getXAxis(0);
        xAxis.getTick().setVisible(true);
        xAxis.getTitle().setVisible(false);
        xAxis.getTitle().setText(Messages.ColumnMonth);
        xAxis.getGrid().setStyle(LineStyle.NONE);
        xAxis.enableCategory(true);

        IAxis yAxis = chart.getAxisSet().getYAxis(0);
        yAxis.getTitle().setVisible(false);
        yAxis.getTick().setVisible(get(ChartShowYAxisConfig.class).getIsShowYAxis());
        yAxis.setPosition(Position.Secondary);

        chart.getPlotArea().addTraverseListener(event -> event.doit = true);
        ((IPlotArea) chart.getPlotArea()).addCustomPaintListener(new TimeGridPaintListener(chart));

        container.layout();

        return container;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Supplier<List<TransactionPair<?>>> getUpdateTask()
    {
        Interval interval = get(ReportingPeriodConfig.class).getReportingPeriod().toInterval(LocalDate.now());
        ClientFilter clientFilter = get(ClientFilterConfig.class).getSelectedFilter();
        CacheKey key = new CacheKey(TransactionPair.class, clientFilter, interval);

        return () -> (List<TransactionPair<?>>) getDashboardData().getCache().computeIfAbsent(key,
                        k -> clientFilter.filter(getClient()).getAllTransactions());
    }

    @Override
    public void update(List<TransactionPair<?>> transactions)
    {
        try
        {
            title.setText(TextUtil.tooltip(getWidget().getLabel()));

            chart.suspendUpdate(true);

            GridData data = (GridData) chart.getLayoutData();

            int oldHeight = data.heightHint;
            int newHeight = get(ChartHeightConfig.class).getPixel();

            if (oldHeight != newHeight)
            {
                data.heightHint = newHeight;
                title.getParent().layout(true);
                title.getParent().getParent().layout(true);
            }

            chart.getTitle().setText(title.getText());

            for (ISeries s : chart.getSeriesSet().getSeries())
                chart.getSeriesSet().deleteSeries(s.getId());

            ChartType chartType = get(ChartTypeConfig.class).getValue();

            toolTip.setDefaultValueFormat(chartType == ChartType.COUNT || chartType == ChartType.COUNT_BY_YEAR
                            ? new DecimalFormat("#") //$NON-NLS-1$
                            : new AmountNumberFormat());

            IAxis xAxis = chart.getAxisSet().getXAxis(0);
            Interval interval = get(ReportingPeriodConfig.class).getReportingPeriod().toInterval(LocalDate.now());

            chart.getAxisSet().getYAxis(0).getTick()
                            .setFormat(chartType == ChartType.COUNT || chartType == ChartType.COUNT_BY_YEAR
                                            ? new DecimalFormat("#") //$NON-NLS-1$
                                            : new ThousandsNumberFormat());
            chart.getAxisSet().getYAxis(0).getTick().setVisible(get(ChartShowYAxisConfig.class).getIsShowYAxis());

            if (this.isAggregateByYear())
            {
                List<Year> years = interval.getYears();
                chart.setData(years);
                xAxis.setCategorySeries(years.stream().map(year -> String.valueOf(year.getValue()))
                                .collect(Collectors.toList()).toArray(new String[0]));
            }
            else
            {
                List<YearMonth> yearMonths = interval.getYearMonths();
                chart.setData(yearMonths);
                xAxis.setCategorySeries(yearMonths.stream().map(ym -> String.valueOf(ym.getMonthValue()))
                                .collect(Collectors.toList()).toArray(new String[0]));
            }

            createSeries(chartType, interval, transactions, PortfolioTransaction.Type.BUY, Colors.ICON_BLUE);

            createSeries(chartType, interval, transactions, PortfolioTransaction.Type.DELIVERY_INBOUND,
                            Colors.brighter(Colors.ICON_BLUE));

            createSeries(chartType, interval, transactions, PortfolioTransaction.Type.SELL, Colors.ICON_ORANGE);

            createSeries(chartType, interval, transactions, PortfolioTransaction.Type.DELIVERY_OUTBOUND,
                            Colors.brighter(Colors.ICON_ORANGE));

        }
        finally
        {
            chart.suspendUpdate(false);
        }

        // Chart#adjustRange must run outside / after #suspendUpdate(false)
        // because otherwise the ranges are adjusted without taking the stacked
        // series into account

        try
        {
            chart.setRedraw(false);
            chart.getAxisSet().adjustRange();
        }
        finally
        {
            chart.setRedraw(true);
        }
    }

    private void createSeries(ChartType chartType, Interval interval, List<TransactionPair<?>> transactions,
                    PortfolioTransaction.Type type, Color color)
    {
        IBarSeries barSeries = (IBarSeries) chart.getSeriesSet().createSeries(SeriesType.BAR, type.toString());
        barSeries.setDescription(type.toString());
        List<Year> years = interval.getYears();
        List<YearMonth> yearMonths = interval.getYearMonths();

        double[] series = new double[this.isAggregateByYear() ? years.size() : yearMonths.size()];

        for (TransactionPair<?> pair : transactions) // NOSONAR
        {
            if (!interval.contains(pair.getTransaction().getDateTime()))
                continue;

            Optional<TransactionPair<PortfolioTransaction>> tx = pair.withPortfolioTransaction();

            if (!tx.isPresent())
                continue;

            if (type != tx.get().getTransaction().getType())
                continue;

            int indexOf = this.isAggregateByYear() ? years.indexOf(Year.from(tx.get().getTransaction().getDateTime()))
                            : yearMonths.indexOf(YearMonth.from(tx.get().getTransaction().getDateTime()));
            if (indexOf >= 0)
            {
                switch (chartType)
                {
                    case COUNT:
                    case COUNT_BY_YEAR:
                        series[indexOf] += 1;
                        break;
                    case SUM:
                    case SUM_BY_YEAR:
                        series[indexOf] += (tx.get().getTransaction().getMonetaryAmount(converter).getAmount()
                                        / Values.Amount.divider());
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            }
        }

        barSeries.setYSeries(series);
        barSeries.setBarColor(color);
        barSeries.setBarPadding(25);
        barSeries.enableStack(true);
    }

    @Override
    public Control getTitleControl()
    {
        return title;
    }

    private boolean isAggregateByYear()
    {
        ChartType chartType = get(ChartTypeConfig.class).getValue();

        return chartType == ChartType.SUM_BY_YEAR || chartType == ChartType.COUNT_BY_YEAR;
    }
}
