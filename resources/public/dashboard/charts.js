/*
  Displays a set of counters and a sparkline for a metric (JSON that's
  fetched via AJAX).
*/
function counterAndSparkline() {
    // Defaults

    // How many data points to retain for the sparkline
    var nHistorical = 60;
    // How often to poll for new data
    var pollingInterval = 5000;
    // Width of the sparkline
    var width = 200;
    // Height of the sparkline
    var height = 40;
    // What URL to poll for JSON
    var url = null;
    // Function used to return a number from the JSON response;
    // defaults to the identity function
    var snag = function(j) { return j; };
    // How to format the snagged number for display
    var format = d3.format(",.1f");
    // Top-line label to use
    var description = "\u00a0";
    // Sub-heading describing the metric
    var addendum = "\u00a0";
    // What DOM element to use
    var container = null;

    function chart() {
        var n = nHistorical;
        var duration = pollingInterval;
        var w = width;
        var h = height;
        var now = new Date();

        // X axis, chronological scale
        var x = d3.time.scale()
            .domain([now - n*duration, now])
            .range([0, w]);

        // Y axis, linear scale
        var y = d3.scale.linear()
            .range([h, 0]);

        // Initial data set. Datums are maps with a time and a
        // value. The initial data set is seeded to have values of 0
        // spread out uniformly across the x-axis.
        var data = d3.range(n).map(function(i) {
            return {time: x.ticks(n)[i],
                    value: 0};
        });
        var data = [];

        // Function that extracts values from a datum
        var value = function(d) { return d.value; };

        // SVG pathing computation, plotting time vs. value. We use
        // linear interpolation to provide better visibility of data
        // points. For prettier lines, try "basis" or "monotone".
        var line = d3.svg.line()
            .interpolate("linear")
            .x(function(d) { return x(d.time); })
            .y(function(d) { return y(d.value); });

        // The "box" represents all DOM elements for this metrics'
        // display
        var box = d3.select(container).append("div")
            .attr("class", "counterbox");

        // Add the description and addendum
        box.append("div")
            .attr("class", "counterdesc")
            .text(description);
        box.append("div")
            .attr("class", "counteraddendum")
            .text(addendum);

        // Add the placeholder for the actual metric value
        var counter = box.append("div")
            .attr("class", "countertext");

        // Add DOM elements for min/median/max values
        var details = box.append("table");
        var detailed_stats = details.append("tr");
        detailed_stats.append("td").attr("class", "countermin");
        detailed_stats.append("td").attr("class", "countermed");
        detailed_stats.append("td").attr("class", "countermax");
        var details_legend = details.append("tr").attr("class", "counterlegend");
        details_legend.append("td").text("min");
        details_legend.append("td").text("med");
        details_legend.append("td").text("max");

        // Add an SVG element for the sparkline
        var svg = box.append("svg")
            .attr("width", w)
            .attr("height", h)
            .append("g");

        // Add an SVG clip path, to make the scolling sparkline look
        // nicer.
        svg.append("defs").append("clipPath")
            .attr("id", "clip")
            .append("rect")
            .attr("width", w)
            .attr("height", h);

        // The actual SVG line, associated with our clip path and
        // seeded with our initial data
        var path = svg.append("g")
            .attr("clip-path", "url(#clip)")
            .append("path")
            .attr("d", line(data))
            .attr("class", "line");

        // Footer that mentiones the time-scale of the sparkline
        var timescale = box.append("div")
            .attr("class", "countertimescale")
            .text(d3.format(".1f")(duration * n / 60000) + " min trend");

        // Update function
        function tick() {
            // Grab our metric over HTTP
            d3.json(url, function cb(res) {
                var now = new Date();

                // Append the new datum to our data set.
                //
                // If we didn't get a response, add a 0.
                if (res != null) {
                    // Use the user-supplied callback to parse out a
                    // value
                    data.push({time: now, value: snag(res)});
                } else {
                    data.push({time: now, value: 0});
                }

                var datavals = data.map(value);

                // Update our axes (axises?)

                // Scale the x-axis to go from the second datapoint to
                // the next-to-last datapoint. We do this to make the
                // scolling smooth.
                //
                // By having the x-axis exclude the
                // most recent datum (the one we just received), that
                // part of the line will be rendered off the side of
                // the graph, invisible.
                //
                // We can then animate the line to scroll to the left,
                // bringing the new datum into view smoothly.
                //
                // So much effort just to have smooth scrolling...
                if (data.length > 1) {
                    x.domain([now - (n-2)*duration, data[data.length-2].time]);
                } else {
                    // If we don't have 2 data points, estimate 2 data
                    // points out by adding our duration to the
                    // timestamp of the last data point.
                    x.domain([now - (n-2)*duration, data[data.length-1].time - duration]);
                };

                // Scale the y-axis to go from the min value to max
                // value, and round to nice, even numbers to reduce
                // flickering from the scale changing too much
                y.domain([d3.min(datavals), d3.max(datavals)]).nice();

                // Display the most recent datum, or error text if
                // none was available
                if (res != null) {
                    box.select(".countertext")
                        .text(format(data[data.length-1].value));
                } else {
                    box.select(".countertext")
                        .text("?");
                }

                // Display the min/median/max of the data set
                box.select(".countermin")
                    .text(format(d3.min(datavals)));
                box.select(".countermax")
                    .text(format(d3.max(datavals)));
                box.select(".countermed")
                    .text(format(d3.median(datavals)));

                // Redraw our line. We draw it in-place, with the
                // newest data point off the screen. We then
                // transition the line to the left, bringing in the
                // new datum.
                //
                // We use a "linear" easing function to make the
                // scolling perfectly smooth relative to the polling
                // interval; the scroll rate matches the data update
                // rate (in theory, anyways)
                //
                // After the transition is done, call the tick()
                // function again to re-update.
                svg.select(".line")
                    .attr("d", line(data))
                    .attr("transform", null)
                    .transition()
                    .duration(duration)
                    .ease("linear")
                    .attr("transform", "translate(" + x(now-(n-1)*duration) + ")")
                    .each("end", tick);

                // pop the old data point off the front
                if (data.length > n) {
                    data.shift();
                };
            });
        };

        // Start the update routine.
        tick();
    }

    // Functions allowing for overrides of default values

    chart.width = function(_) {
        if (!arguments.length) return width;
        width = _;
        return chart;
    };

    chart.height = function(_) {
        if (!arguments.length) return height;
        height = _;
        return chart;
    };

    chart.url = function(_) {
        if (!arguments.length) return url;
        url = _;
        return chart;
    };

    chart.snag = function(_) {
        if (!arguments.length) return snag;
        snag = _;
        return chart;
    };

    chart.container = function(_) {
        if (!arguments.length) return container;
        container = _;
        return chart;
    };

    chart.nHistorical = function(_) {
        if (!arguments.length) return nHistorical;
        nHistorical = _;
        return chart;
    };

    chart.pollingInterval = function(_) {
        if (!arguments.length) return pollingInterval;
        pollingInterval = _;
        return chart;
    };

    chart.format = function(_) {
        if (!arguments.length) return format;
        format = _;
        return chart;
    };

    chart.description = function(_) {
        if (!arguments.length) return description;
        description = _;
        return chart;
    };

    chart.addendum = function(_) {
        if (!arguments.length) return addendum;
        addendum = _;
        return chart;
    };

    return chart;
}
