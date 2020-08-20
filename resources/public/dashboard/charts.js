// Formatting middleware that collapses small values to 0
function clampToZero(f, window) {
    return function(n) {
        return f(Math.abs(n) < window ? 0 : n);
    };
};

/*
 Displays a set of counters and a sparkline for a metric
 - meter.description: Top-line label to use
 - meter.addendum: Sub-heading describing the metric
 - meter.value: The current value
 - meter.format: How to format the value for display
 */
function counterAndSparkline(meter, options) {
    var format = d3.format(meter.format);
    if(meter.clampToZero) {
        format = clampToZero(format, meter.clampToZero);
    }

    // Defaults

    // How many data points to retain for the sparkline
    var n = options.nHistorical;
    // How often to poll for new data
    var duration = options.pollingInterval;

    var margin = {top: 10, right: 0, bottom: 10, left: 50};
    // Width of the sparkline
    var w = options.width - margin.left;
    // Height of the sparkline
    var h = options.height - margin.top - margin.bottom;

    var now = new Date();
    var data = [];

    // X axis, chronological scale
    var x = d3.scaleTime()
            .domain([now - n*duration, now])
            .range([0, w]);

    // Y axis, linear scale
    var y = d3.scaleLinear()
            .range([h, 1]);

    // SVG pathing computation, plotting time vs. value. We use
    // linear interpolation to provide better visibility of data
    // points. For prettier lines, try "basis" or "monotone".
    var line = d3.area()
            .curve(d3.curveLinear)
            .x(function(d) { return 1+x(d.time); })
            .y1(h+1)
            .y0(function(d) { return y(d.value); });

    // The "box" represents all DOM elements for this metrics'
    // display
    var box = d3.select(options.container).append("tr")
            .attr("class", "counterbox");

    // Add the description and addendum
    var label = box.append("td");
    label.append("div")
        .attr("class", "counterdesc")
        .html(meter.description);
    label.append("div")
        .attr("class", "counteraddendum")
        .html(meter.addendum);

    // Add the placeholder for the actual metric value
    box.append("td").attr("class", "countertext");

    // Initial text for the counters
    box.select(".countertext").html("?");

    // Add an SVG element for the sparkline
    var svg = box.append("td").append("svg")
            .attr("width", w + margin.left + margin.right)
            .attr("height", h + margin.top + margin.bottom)
            .append("g")
            .attr("transform", "translate(" + margin.left + "," + margin.top + ")");

    var yaxis = svg.append("g")
            .attr("class", "y axis")
            .attr("transform", "translate(-1,0)")
            .call(y.axis = d3.axisLeft(y).ticks(3));
            //.call(y.axis = d3.svg.axis().scale(y).orient("left").ticks(3));

    // Add an SVG clip path, to make the scolling sparkline look nicer.
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

    // Redraw
    function redraw(now, data) {
        var datavals = data.map(function(d) { return d.value; });

        // Update our axes (axises?)

        // Scale the x-axis to go from the second datapoint to the
        // next-to-last datapoint. We do this to make the scolling
        // smooth.
        //
        // By having the x-axis exclude the most recent datum (the
        // one we just received), that part of the line will be
        // rendered off the side of the graph, invisible.
        //
        // We can then animate the line to scroll to the left,
        // bringing the new datum into view smoothly.
        //
        // So much effort just to have smooth scrolling...
        //
        // If we don't have 2 data points, estimate 2 data
        // points out by adding our duration to the timestamp
        // of the last data point.
        var xmin = now - (n-2) * duration;
        var xmax = (data.length > 1) ? data[data.length-2].time : data[data.length-1].time - duration;
        x.domain([xmin, xmax]);

        // Scale the y-axis to go from the min value to max value,
        // and round to nice, even numbers to reduce flickering
        // from the scale changing too much
        y.domain([d3.min(datavals), d3.max(datavals)]).nice();

        // Redraw our line. We draw it in-place, with the newest
        // data point off the screen. We then transition the line
        // to the left, bringing in the new datum.
        //
        // We use a "linear" easing function to make the scolling
        // perfectly smooth relative to the polling interval; the
        // scroll rate matches the data update rate (in theory,
        // anyways)
        svg.select(".line")
           .attr("d", line(data))
           .transition()
           .duration(duration)
           .ease(d3.easeLinear)
           .attr("transform", "translate(" + x(xmin + duration) + ")");

        yaxis.transition()
             .call(y.axis
                    .ticks(3)
                    .tickSize(6, 0, 0)
                    .tickFormat(format));
    };

    // Return a function which will update the widget
    return function update(newValue) {
        var now = new Date();
        var valueOrZero = (newValue != null) ? newValue : 0;

        var countertext = (newValue != null) ? format(newValue) : "?";
        box.select(".countertext").html(countertext);

        data.push({time: now, value: valueOrZero});
        redraw(now, data);
        // pop the old data point off the front
        if (data.length > n) { data.shift(); };
    };
}
