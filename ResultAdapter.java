package com.example.speechrecognitionapp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;

public class ResultAdapter extends ArrayAdapter<Result> implements View.OnClickListener {

    private ArrayList<Result> dataSet;
    Context mContext;

    private static class ViewHolder {
        TextView txtLabel;
        TextView txtValue;
        ProgressBar progressBar;
    }

    public ResultAdapter(ArrayList<Result> data, Context context) {
        super(context, R.layout.row_item, data);
        this.dataSet = data;
        this.mContext = context;
    }

    @Override
    public void onClick(View view) {

    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Result result = getItem(position);
        ViewHolder viewHolder;

        if (convertView == null) {
            viewHolder = new ViewHolder();
            convertView = View.inflate(getContext(), R.layout.row_item, null);
            viewHolder.txtLabel = (TextView) convertView.findViewById(R.id.txtLabel);
            viewHolder.txtValue = (TextView) convertView.findViewById(R.id.txtValue);
            viewHolder.progressBar = (ProgressBar) convertView.findViewById(R.id.progressBar);
            convertView.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) convertView.getTag();
        }
        viewHolder.txtLabel.setText(result.getLabel());
        viewHolder.txtValue.setText(String.format("%.2f", result.getConfidence() * 100));
        viewHolder.progressBar.setProgress((int) (result.getConfidence() * 100));

        return convertView;
    }
}
