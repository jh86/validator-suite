define(["lib/Logger", "model/assertion", "collection/Collection"], function (Logger, Assertion, Collection) {

    "use strict";

    var logger = new Logger("Assertions"),
        Assertions;

    Assertions = Collection.extend({

        model: Assertion,

        comparator: function (o1, o2) {
            if (o1.get("severity") === o2.get("severity")) {
                return o1.get("occurrences") === o2.get("occurrences") ?
                        (o1.get("title") > o2.get("title") ? +1 : -1) :
                        (o1.get("occurrences") > o2.get("occurrences") ? -1 : +1);
            }
            if (o1.get("severity") === "error") {
                return -1;
            }
            if (o2.get("severity") === "error") {
                return +1;
            }
            if (o1.get("severity") === "warning" && o2.get("severity") === "info") {
                return -1;
            }
            if (o1.get("severity") === "info" && o2.get("severity") === "warning") {
                return +1;
            }
        }

    });

    Assertions.View = Assertions.View.extend({

        attributes: {
            id: "assertions"
        },

        sortParams: [],

        filterOn: function (assertorId) {
            this.filter = function (assertion) {
                return assertion.get("assertor") === assertorId;
            };
            this.render();
        },

        emptyMessage: "No assertions to show." // assertions.empty

    });

    return Assertions;

});