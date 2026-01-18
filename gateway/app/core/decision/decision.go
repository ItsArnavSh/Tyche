package decision

import (
	"gateway/app/util/bucket"
	"gateway/app/util/transaction"
)

//Todo: Decide when to start the decision maker
// When started, pick the top 3 stocks which you can buy 4 units of.
// Top in terms of confidence
//	Now in that iteration you set the goal to buy that
// So for each of the top 3, set a Max Buy, set at 4, unless conf is very high suddenly
// And prioritywise place orders and set a max loss and send that to Monitor
// Now just try to buy 1 till the conf is increasing, else pick something else
//
// Or actually just keep it dynamic like pick the top one, and if it varies from top then sth idk lemme see later

type DecisionLayer struct {
	transaction transaction.TransactionHandler
	bucket      bucket.Bucket
}

func NewDecisionLayer(th transaction.TransactionHandler, bucket bucket.Bucket) DecisionLayer {
	return DecisionLayer{
		transaction: th,
		bucket:      bucket,
	}
}

func StartDecisionServer() {

}
