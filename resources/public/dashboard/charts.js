/*
  Displays a set of counters and a sparkline for a metric (JSON that's
  fetched via AJAX).
*/
// Top-line label to use

// Sub-heading describing the metric

// What URL to poll for JSON

// How to format the snagged number for display

// Function used to return a number from the JSON response;
// defaults to the identity function
function counterAndSparkline(description, addendum, url, format, snag, options) {
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
    var x = d3.time.scale()
            .domain([now - n*duration, now])
            .range([0, w]);

    // Y axis, linear scale
    var y = d3.scale.linear()
            .range([h, 1]);

    // SVG pathing computation, plotting time vs. value. We use
    // linear interpolation to provide better visibility of data
    // points. For prettier lines, try "basis" or "monotone".
    var line = d3.svg.area()
            .interpolate("linear")
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
        .html(description);
    label.append("div")
        .attr("class", "counteraddendum")
        .html(addendum);

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
            .call(y.axis = d3.svg.axis().scale(y).orient("left").ticks(3));

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
        //
        // After the transition is done, call the tick() function
        // again to re-update.
        svg.select(".line")
           .attr("d", line(data))
           .transition()
           .duration(duration)
           .ease("linear")
           .attr("transform", "translate(" + x(xmin + duration) + ")")
           .each("end", tick);

        yaxis.transition()
             .call(y.axis
                    .ticks(3)
                    .tickSize(6, 0, 0)
                    .tickFormat(format));
    };

    // Update function
    function tick() {
        // Grab our metric over HTTP
        d3.json(url, function cb(res) {
            // Append the new datum to our data set.
            //
            // If we didn't get a response, add a 0. otherwise use the
            // user-supplied callback to parse out a value
            var now = new Date();
            var value = (res != null) ? snag(res) : 0;

            var countertext = (res != null) ? format(value) : "?";
            box.select(".countertext").html(countertext);

            data.push({time: now, value: value});
            redraw(now, data);
            // pop the old data point off the front
            if (data.length > n) { data.shift(); };

        });
    };

    // Start the update routine. We randomize the startup time
    // to introduce jitter between this chart and other charts
    // rendered on the same page. Otherwise, you could end up
    // with many simultaneous AJAX requests.
    setTimeout(tick, Math.ceil(Math.random() * 5000));
}
